package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class BackupFileReaderTest {
    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("hail-backup").toFile()
        try {
            File(root, "v1").mkdir()
            File(root, "v1/tags.json").writeText("[]")
            File(root, "v1/apps.json").writeText("[]")
            test(root)
        } finally { root.deleteRecursively() }
    }

    @Test fun ignoresExcludedOversizedFilesBeforeReadingOrValidatingThem() = withRoot { root ->
        RandomAccessFile(File(root, "unused.bin"), "rw").use { it.setLength(9L * 1024 * 1024) }
        val files = BackupFileReader.read(root, FullBackupSelection(includeAdditionalFiles = false))
        assertEquals(setOf("v1/tags.json", "v1/apps.json"), files.map { it.path }.toSet())
    }

    @Test fun rejectsSelectedOversizedFileBeforeOpeningIt() = withRoot { root ->
        val file = File(root, "large.bin")
        RandomAccessFile(file, "rw").use { it.setLength(9L * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { BackupFileReader.read(root) }
    }

    @Test fun rejectsTooManyFilesAndOutsideSymlink() = withRoot { root ->
        repeat(253) { File(root, "file-$it").writeText("") }
        assertThrows(IllegalArgumentException::class.java) { BackupFileReader.read(root) }
        root.listFiles()!!.filter { it.isFile }.forEach { it.delete() }
        val outside = Files.createTempFile("hail-outside", ".json")
        try {
            Files.createSymbolicLink(File(root, "escape").toPath(), outside)
            assertThrows(IllegalArgumentException::class.java) { BackupFileReader.read(root) }
        } finally { Files.delete(outside) }
    }
}
