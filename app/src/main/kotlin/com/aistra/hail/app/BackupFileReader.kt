package com.aistra.hail.app

import java.io.ByteArrayOutputStream
import java.io.File

/** Inspect metadata first; neither excluded nor oversized files are loaded into memory. */
internal object BackupFileReader {
    private const val MAX_FILE = 8L * 1024 * 1024
    private const val MAX_TOTAL = 40L * 1024 * 1024
    private const val MAX_FILES = 254
    private val required = setOf("v1/tags.json", "v1/apps.json")

    fun read(rootDirectory: File, selection: FullBackupSelection = FullBackupSelection()): List<FullBackupFile> {
        val root = rootDirectory.canonicalFile
        val selected = mutableListOf<Pair<String, File>>()
        var declaredTotal = 0L
        root.walkTopDown().onEnter {
            require(it.canonicalFile == it.absoluteFile) { "App data contains a symbolic directory" }
            true
        }.filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (!selection.includeAdditionalFiles && relative !in required) return@forEach
            val canonical = file.canonicalFile
            require(canonical == file.absoluteFile && canonical.path.startsWith(root.path + File.separator)) {
                "App data contains an unsafe file path"
            }
            require(selected.size < MAX_FILES) { "Backup contains too many app files" }
            require(file.length() <= MAX_FILE) { "App file is too large: $relative" }
            declaredTotal += file.length()
            require(declaredTotal <= MAX_TOTAL) { "App data is too large to back up" }
            selected += relative to canonical
        }
        require(selected.map { it.first }.containsAll(required)) { "Hail app data is incomplete" }
        var actualTotal = 0L
        return selected.map { (path, file) ->
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Backup cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= MAX_FILE) { "App file grew beyond the backup limit" }
                    actualTotal += count
                    require(actualTotal <= MAX_TOTAL) { "App data grew beyond the backup limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            FullBackupFile(path, bytes)
        }
    }
}
