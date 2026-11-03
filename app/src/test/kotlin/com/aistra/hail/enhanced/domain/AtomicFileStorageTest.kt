package com.aistra.hail.enhanced.domain

import com.aistra.hail.utils.AtomicFileStorage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicFileStorageTest {
    @Test fun replacesReadOnlyTargetWithoutDeletingOldFileFirst() {
        val dir = Files.createTempDirectory("hail-atomic").toFile()
        try {
            val target = object : File(dir, "profiles.json") {
                override fun delete(): Boolean = throw AssertionError("Must replace atomically; never unlink existing data")
            }
            target.writeText("old")
            AtomicFileStorage.write(target, "new".toByteArray())
            assertEquals("new", target.readText())
            assertEquals(listOf("profiles.json"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedRenameKeepsExistingDirectoryContentsAndCleansTemporaryFile() {
        val dir = Files.createTempDirectory("hail-atomic").toFile()
        try {
            val target = File(dir, "profiles.json").apply { mkdir() }
            File(target, "old").writeText("preserved")
            assertThrows(java.io.IOException::class.java) { AtomicFileStorage.write(target, byteArrayOf(1)) }
            assertEquals("preserved", File(target, "old").readText())
            assertEquals(listOf("profiles.json"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
}
