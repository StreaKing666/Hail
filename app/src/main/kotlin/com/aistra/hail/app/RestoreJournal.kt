package com.aistra.hail.app

import com.aistra.hail.utils.AtomicFileStorage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

internal class RecoveryRequiredException(cause: Exception) :
    IllegalStateException("Recovery remains pending; the snapshot and journal have been retained", cause)

/** Same journal format as .11. An unchecked snapshot is never passed to the caller. */
internal class RestoreJournal(private val directory: File) {
    private val snapshot get() = File(directory, "pre-restore.zip")
    private val journal get() = File(directory, "journal.json")

    fun execute(bytes: ByteArray, apply: () -> Unit, rollback: (ByteArray) -> Unit) {
        check(!journal.exists()) { "An earlier restore must be recovered first" }
        require(bytes.size <= MAX_SNAPSHOT) { "Restore snapshot is too large" }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create recovery storage" }
        AtomicFileStorage.write(snapshot, bytes)
        val hash = hash(bytes)
        writeJournal("APPLYING", hash)
        try {
            apply()
            writeJournal("COMMITTED", hash)
        } catch (error: Exception) {
            try { rollback(bytes); cleanup() }
            catch (rollbackError: Exception) {
                error.addSuppressed(rollbackError)
                throw RecoveryRequiredException(error)
            }
            throw error
        }
        try { cleanup() } catch (error: Exception) { throw RecoveryRequiredException(error) }
    }

    fun recover(rollback: (ByteArray) -> Unit) {
        if (!journal.exists()) { cleanup(); return }
        val record = JSONObject(String(readBounded(journal, 8192), Charsets.UTF_8))
        when (record.optString("state")) {
            "COMMITTED" -> cleanup()
            "APPLYING" -> {
                val bytes = readBounded(snapshot, MAX_SNAPSHOT)
                require(hash(bytes) == record.optString("sha256")) { "Restore recovery snapshot checksum mismatch" }
                rollback(bytes)
                cleanup()
            }
            else -> error("Restore recovery journal is invalid")
        }
    }

    private fun writeJournal(state: String, hash: String) = AtomicFileStorage.write(journal,
        JSONObject().put("state", state).put("snapshot", "pre-restore.zip").put("sha256", hash)
            .put("createdAtEpochMs", System.currentTimeMillis()).toString().toByteArray())

    private fun cleanup() { check(!directory.exists() || directory.deleteRecursively()) { "Could not clean recovery storage" } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun readBounded(file: File, limit: Int): ByteArray {
        require(file.isFile && file.length() <= limit) { "Recovery file is missing or too large" }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= limit) { "Recovery file grew beyond the limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    companion object { private const val MAX_SNAPSHOT = 48 * 1024 * 1024 }
}
