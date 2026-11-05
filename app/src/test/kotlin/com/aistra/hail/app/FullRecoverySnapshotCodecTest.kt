package com.aistra.hail.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FullRecoverySnapshotCodecTest {
    private fun original() = FullRecoverySnapshot(
        listOf(FullBackupFile("v1/tags.json", "original raw tags".toByteArray()),
            FullBackupFile("v1/apps.json", "original raw apps".toByteArray()),
            FullBackupFile("nested/local.dat", byteArrayOf(0, 1, -1))),
        TagStateSnapshot(listOf(TagStateTag(" Default ", 0), TagStateTag(" " + "x".repeat(129) + " ", -17)),
            listOf(TagStateApp("com.example.app", true, false, 0, emptyList()),
                TagStateApp("com.example.app", false, true, 12, listOf(-17, 0, -17)))),
        linkedMapOf("working_mode" to "su_hide", "biometric_login" to true,
            "auto_freeze_after_lock" to true, "private_token" to "local-only",
            "integer" to 7, "long" to 7L, "float" to Float.fromBits(0x7fc00017),
            "infinity" to Float.POSITIVE_INFINITY, "set" to linkedSetOf("one", " two ")))

    private fun assertExact(expected: FullRecoverySnapshot, actual: FullRecoverySnapshot) {
        assertEquals(expected.tagState, actual.tagState)
        assertEquals(expected.files.map { it.path }.toSet(), actual.files.map { it.path }.toSet())
        expected.files.forEach { file -> assertArrayEquals(file.bytes, actual.files.single { it.path == file.path }.bytes) }
        assertEquals(expected.preferences.keys, actual.preferences.keys)
        expected.preferences.forEach { (key, value) ->
            if (value is Float) assertEquals(value.toRawBits(), (actual.preferences.getValue(key) as Float).toRawBits())
            else { assertEquals(value.javaClass, actual.preferences.getValue(key).javaClass); assertEquals(value, actual.preferences[key]) }
        }
    }

    @Test fun exactInternalRoundTripPreservesLegacyStateRawFilesAndEveryPreferenceType() {
        val state = original()
        assertExact(state, FullRecoverySnapshotCodec.decode(FullRecoverySnapshotCodec.encode(state)))
    }

    @Test fun journalRecoversExactStateAfterProcessDeathOrFailedRollback() {
        for (processDeath in listOf(true, false)) {
            val directory = Files.createTempDirectory("hail-full-exact").toFile()
            val state = original()
            val bytes = FullRecoverySnapshotCodec.encode(state)
            var memory = state.tagState
            val files = state.files.associate { it.path to it.bytes }.toMutableMap()
            val preferences = state.preferences.toMutableMap()
            fun rollback(snapshot: ByteArray) {
                val decoded = FullRecoverySnapshotCodec.decode(snapshot)
                files.clear(); files.putAll(decoded.files.associate { it.path to it.bytes })
                preferences.clear(); preferences.putAll(decoded.preferences)
                memory = decoded.tagState
            }
            try {
                val failure = if (processDeath) AssertionError::class.java else RecoveryRequiredException::class.java
                assertThrows(failure) {
                    RestoreJournal(directory).execute(bytes, {
                        files.remove("nested/local.dat"); files["new-from-import"] = byteArrayOf(9)
                        preferences.clear(); preferences["working_mode"] = "default"
                        memory = TagStateSnapshot(emptyList(), emptyList())
                        if (processDeath) throw AssertionError("process death") else error("apply failed")
                    }, { error("rollback storage unavailable") })
                }
                assertTrue(File(directory, "journal.json").exists())
                RestoreJournal(directory).recover(::rollback)
                assertExact(state, FullRecoverySnapshot(files.map { FullBackupFile(it.key, it.value) }, memory, preferences))
                assertFalse(directory.exists())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun unknownPrivateVersionAndTamperedEntryFailClosed() {
        val entries = unzip(FullRecoverySnapshotCodec.encode(original()))
        val unknown = entries.toMutableMap().apply {
            this["recovery.json"] = JSONObject(String(getValue("recovery.json"))).put("version", 2).toString().toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { FullRecoverySnapshotCodec.decodeOrNull(zip(unknown)) }
        val tampered = entries.toMutableMap().apply { this["files/nested/local.dat"] = byteArrayOf(5) }
        assertThrows(IllegalArgumentException::class.java) { FullRecoverySnapshotCodec.decode(zip(tampered)) }
    }

    @Test fun unsafePathsEntrySizesAndCountsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FullRecoverySnapshotCodec.decode(zip(mapOf("files/../outside" to byteArrayOf(1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullRecoverySnapshotCodec.decode(zip(mapOf("oversized" to ByteArray(8 * 1024 * 1024 + 1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            FullRecoverySnapshotCodec.encode(original().copy(files = List(254) { FullBackupFile("file$it", byteArrayOf(0)) }))
        }
    }

    @Test fun legacyPortablePendingSnapshotsRemainRecognizableWithoutPrivateFallbackForUnknownVersion() {
        val portable = FullBackupArchive.encode(listOf(
            FullBackupFile("v1/tags.json", """[{"tag":"Default","id":0}]""".toByteArray()),
            FullBackupFile("v1/apps.json", "[]".toByteArray()),
            FullBackupFile("local:name", byteArrayOf(1))), emptyMap<String, Any>(), "com.example.hail", "old", 13)
        assertNull(FullRecoverySnapshotCodec.decodeOrNull(portable))
    }

    @Test fun alteredJournalSnapshotIsNeverApplied() {
        val directory = Files.createTempDirectory("hail-full-checksum").toFile()
        try {
            val bytes = FullRecoverySnapshotCodec.encode(original())
            try { RestoreJournal(directory).execute(bytes, { throw AssertionError("process death") }, {}) }
            catch (_: AssertionError) { }
            File(directory, "pre-restore.zip").appendBytes(byteArrayOf(1))
            assertThrows(IllegalArgumentException::class.java) {
                RestoreJournal(directory).recover { fail("Unchecked bytes must not be applied") }
            }
            assertTrue(File(directory, "journal.json").exists())
        } finally { directory.deleteRecursively() }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) { val entry = input.nextEntry ?: break; put(entry.name, input.readBytes()) }
        }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
}
