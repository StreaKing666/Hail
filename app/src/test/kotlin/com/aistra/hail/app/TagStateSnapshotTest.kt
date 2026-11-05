package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class TagStateSnapshotTest {
    private fun snapshot() = TagStateSnapshot(
        listOf(TagStateTag("Default", 0), TagStateTag(" exact name ", -17)),
        listOf(TagStateApp("com.example.untagged", true, false, 12, emptyList()),
            TagStateApp("com.example.tagged", false, true, 0, listOf(-17, 0, -17))))

    @Test fun roundTripPreservesLegacyEmptyAssignmentsFlagsUserIdsAndExactOrder() {
        val expected = snapshot()
        val actual = TagStateSnapshotCodec.decode(TagStateSnapshotCodec.encode(expected))
        assertEquals(expected, actual)
        assertTrue(actual.apps.first().tagIds.isEmpty())
        assertEquals(listOf(-17, 0, -17), actual.apps.last().tagIds)
        assertEquals(12, actual.apps.first().userId)
        assertTrue(actual.apps.first().pinned)
        assertTrue(actual.apps.last().whitelisted)
    }

    @Test fun snapshotsDefensivelyCopyMutableCollections() {
        val ids = mutableListOf(0)
        val apps = mutableListOf(TagStateApp("com.example.app", false, false, 0, ids))
        val tags = mutableListOf(TagStateTag("Default", 0))
        val state = TagStateSnapshot(tags, apps)
        ids.clear(); apps.clear(); tags.clear()
        assertEquals(listOf(0), state.apps.single().tagIds)
        assertEquals(0, state.tags.single().id)
        assertThrows(UnsupportedOperationException::class.java) {
            (state.apps.single().tagIds as MutableList<Int>).clear()
        }
    }

    @Test fun invalidSchemaAndTypesCannotBecomeRollbackState() {
        val valid = JSONObject(String(TagStateSnapshotCodec.encode(snapshot()), Charsets.UTF_8))
        for (invalid in listOf(
            JSONObject(valid.toString()).put("version", 2),
            JSONObject(valid.toString()).put("version", "1"),
            JSONObject(valid.toString()).put("extra", true),
            JSONObject(valid.toString()).apply { getJSONArray("apps").getJSONObject(0).put("pinned", "true") },
            JSONObject(valid.toString()).apply { getJSONArray("apps").getJSONObject(0).put("userId", -1) },
            JSONObject(valid.toString()).apply { getJSONArray("apps").getJSONObject(0).put("userId", 0.5) },
            JSONObject(valid.toString()).apply { getJSONArray("apps").getJSONObject(0).put("packageName", "bad; command") }
        )) assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.decode(invalid.toString().toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.decode((valid.toString() + " trailing").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.decode("[]".toByteArray())
        }
    }

    @Test fun encodeAndDecodeEnforceBoundsBeforeBuildingLargePayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.encode(TagStateSnapshot(List(2001) { TagStateTag("tag", it) }, emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.encode(TagStateSnapshot(emptyList(),
                List(20001) { TagStateApp("com.example.app", false, false, it, emptyList()) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.encode(TagStateSnapshot(emptyList(),
                listOf(TagStateApp("com.example.app", false, false, 0, List(100001) { 0 }))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.encode(TagStateSnapshot(listOf(TagStateTag("x".repeat(1025), 0)), emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.encode(TagStateSnapshot(List(2000) { TagStateTag("\u0001".repeat(1024), it) }, emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.encode(TagStateSnapshot(emptyList(),
                listOf(TagStateApp("x".repeat(256), false, false, 0, emptyList()))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.decode(ByteArray(8 * 1024 * 1024 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TagStateSnapshotCodec.decode(("[".repeat(30) + "]".repeat(30)).toByteArray())
        }
    }

    @Test fun journalRejectsTamperedSnapshotAndRetainsFailedRollbackForRetry() {
        val directory = Files.createTempDirectory("hail-tag-state").toFile()
        val bytes = TagStateSnapshotCodec.encode(snapshot())
        try {
            assertThrows(RecoveryRequiredException::class.java) {
                RestoreJournal(directory).execute(bytes, { error("partial write") }, { error("rollback failed") })
            }
            assertTrue(File(directory, "journal.json").isFile)
            var restored: TagStateSnapshot? = null
            RestoreJournal(directory).recover { restored = TagStateSnapshotCodec.decode(it) }
            assertEquals(snapshot(), restored)
            assertFalse(directory.exists())
            try { RestoreJournal(directory).execute(bytes, { throw AssertionError("process death") }, {}) }
            catch (_: AssertionError) { }
            File(directory, "pre-restore.zip").writeBytes(bytes + byteArrayOf(32))
            assertThrows(IllegalArgumentException::class.java) {
                RestoreJournal(directory).recover { fail("Unchecked snapshot must not be applied") }
            }
            assertTrue(File(directory, "journal.json").isFile)
        } finally { directory.deleteRecursively() }
    }
}
