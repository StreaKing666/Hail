package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacyStateReaderTest {
    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("hail-legacy-state").toFile()
        try { test(File(directory, "state.json")) } finally { directory.deleteRecursively() }
    }

    @Test fun missingFilesAndValidEmptyTagsHaveTheirLegacyDefaults() = withFile { file ->
        assertTrue(LegacyStateReader.readApps(file, 10).isEmpty())
        assertEquals(listOf(TagStateTag("Default", 0)), LegacyStateReader.readTags(file, "Default"))
        file.writeText("[]")
        assertEquals(listOf(TagStateTag("Default", 0)), LegacyStateReader.readTags(file, "Default"))
        assertTrue(LegacyStateReader.readApps(file, 10).isEmpty())
    }

    @Test fun malformedLaterRecordCannotExposeOrOverwriteAValidPrefix() = withFile { file ->
        val brokenApps = """[{"package":"com.example.valid","tags":[0]},{"package":"com.example.broken","tags":["oops"]}]"""
        file.writeText(brokenApps)
        assertThrows(IllegalArgumentException::class.java) { LegacyStateReader.readApps(file, 0) }
        assertEquals(brokenApps, file.readText())
        val brokenTags = """[{"tag":"Default","id":0},{"tag":"Broken","id":"oops"}]"""
        file.writeText(brokenTags)
        assertThrows(IllegalArgumentException::class.java) { LegacyStateReader.readTags(file, "Default") }
        assertEquals(brokenTags, file.readText())
    }

    @Test fun legacyMissingUserUsesCallerAndSingleTagAliasIsAccepted() = withFile { file ->
        file.writeText("""[{"package":"com.example.legacy","tag":-17},{"package":"com.example.oldest"}]""")
        val apps = LegacyStateReader.readApps(file, 12)
        assertEquals(listOf(12, 12), apps.map { it.userId })
        assertEquals(listOf(-17), apps.first().tagIds)
        assertEquals(listOf(0), apps.last().tagIds)
        assertFalse(apps.first().pinned)
        assertFalse(apps.first().whitelisted)
    }

    @Test fun explicitUsersFlagsAndEmptyAssignmentsRoundTripWithoutRemapping() = withFile { file ->
        val expected = listOf(TagStateApp("com.example.app", true, false, 12, emptyList()),
            TagStateApp("com.example.app", false, true, 0, listOf(-8, 0)))
        file.writeText(LegacyStateReader.encodeApps(expected))
        assertEquals(expected, LegacyStateReader.readApps(file, 99))
        assertTrue(file.readText().contains("\"userId\":12"))
        assertTrue(LegacyStateReader.readApps(file, 99).first().tagIds.isEmpty())
    }

    @Test fun duplicateAppIdentitiesMergeOrderedTagUnionAndFlagsOnlyWithinTheirUser() = withFile { file ->
        file.writeText("""[
          {"package":"com.example.app","userId":12,"pinned":true,"tags":[-7,0]},
          {"package":"com.example.foreign","userId":0,"tags":[]},
          {"package":"com.example.app","userId":12,"whitelisted":true,"tags":[0,9,-7]},
          {"package":"com.example.app","userId":0,"tags":[4]}
        ]""")
        val apps = LegacyStateReader.readApps(file, 99)
        assertEquals(3, apps.size)
        assertEquals(listOf(-7, 0, 9), apps.first().tagIds)
        assertTrue(apps.first().pinned && apps.first().whitelisted)
        assertEquals("com.example.foreign", apps[1].packageName)
        assertEquals(0, apps.last().userId)
        assertEquals(listOf(4), apps.last().tagIds)
        file.writeText(LegacyStateReader.encodeApps(apps))
        assertEquals(apps, LegacyStateReader.readApps(file, 99))
    }

    @Test fun tagOrderExactNamesAndSignedIdsSurviveWhileDuplicateIdsFail() = withFile { file ->
        val expected = listOf(TagStateTag("Case", -17), TagStateTag("case", 2), TagStateTag(" spaced ", 0))
        file.writeText(LegacyStateReader.encodeTags(expected))
        assertEquals(expected, LegacyStateReader.readTags(file, "Default"))
        file.writeText("""[{"tag":"First","id":5},{"tag":"Second","id":5}]""")
        assertThrows(IllegalArgumentException::class.java) { LegacyStateReader.readTags(file, "Default") }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyStateReader.encodeTags(listOf(TagStateTag("A", 0), TagStateTag("B", 0)))
        }
    }

    @Test fun readersRejectDeepOversizedTrailingAndInvalidUtf8Data() = withFile { file ->
        for (invalid in listOf("[".repeat(30) + "]".repeat(30), "[] trailing", "{}")) {
            file.writeText(invalid)
            assertThrows(IllegalArgumentException::class.java) { LegacyStateReader.readApps(file, 0) }
        }
        file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertThrows(IllegalArgumentException::class.java) { LegacyStateReader.readApps(file, 0) }
        file.outputStream().use { it.write(ByteArray(8 * 1024 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { LegacyStateReader.readApps(file, 0) }
    }

    @Test fun writersRejectInvalidIdentitiesCountsAssignmentsAndNames() {
        for (invalid in listOf(
            TagStateApp("bad; command", false, false, 0, emptyList()),
            TagStateApp("com.example.app", false, false, -1, emptyList()),
            TagStateApp("x".repeat(256), false, false, 0, emptyList()),
            TagStateApp("com.example.app", false, false, 0, List(100001) { 0 })
        )) assertThrows(IllegalArgumentException::class.java) { LegacyStateReader.encodeApps(listOf(invalid)) }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyStateReader.encodeApps(List(20001) { TagStateApp("com.example.app", false, false, it, emptyList()) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyStateReader.encodeTags(List(2001) { TagStateTag("Name", it) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyStateReader.encodeTags(listOf(TagStateTag("x".repeat(1025), 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyStateReader.encodeTags(List(2000) { TagStateTag("\u0001".repeat(1024), it) })
        }
    }
}
