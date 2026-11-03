package com.aistra.hail.app

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import org.json.JSONObject
import java.io.File
import com.aistra.hail.utils.AtomicFileStorage
import java.io.IOException
import java.security.MessageDigest

internal object FullBackupStorage {
    private const val TAGS_PATH = "v1/tags.json"
    private const val APPS_PATH = "v1/apps.json"
    private const val RECOVERY_DIR = "hail-full-restore"
    private const val SNAPSHOT_NAME = "pre-restore.zip"
    private const val JOURNAL_NAME = "journal.json"

    fun createArchive(selection: FullBackupSelection = FullBackupSelection()): ByteArray {
        HailData.saveTags()
        HailData.saveApps()

        val root = app.filesDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val canonical = file.canonicalFile
                require(canonical.path.startsWith(rootPrefix)) { "App data contains an unsafe file path" }
                FullBackupFile(
                    path = canonical.relativeTo(root).path.replace(File.separatorChar, '/'),
                    bytes = canonical.readBytes()
                )
            }
            .toList()

        return FullBackupArchive.encode(
            files = files,
            preferences = PreferenceManager.getDefaultSharedPreferences(app).all,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            selection = selection
        )
    }

    /**
     * The pre-restore snapshot and journal live in noBackupFilesDir, outside the exported app
     * data. If Android kills the process mid-restore, startup restores this snapshot before
     * allowing another operation. The journal never contains device permissions or freeze state.
     */
    fun restore(
        payload: FullBackupPayload,
        mode: FullBackupRestoreMode = FullBackupRestoreMode.MERGE
    ) {
        val recovery = recoveryDirectory()
        if (!recovery.exists() && !recovery.mkdirs()) throw IOException("Could not create restore recovery directory")
        val snapshotFile = File(recovery, SNAPSHOT_NAME)
        val journalFile = File(recovery, JOURNAL_NAME)
        val snapshotBytes = createArchive()
        writeAtomically(snapshotFile, snapshotBytes)
        val snapshotHash = sha256(snapshotBytes)
        writeAtomically(
            journalFile,
            JSONObject()
                .put("state", "APPLYING")
                .put("snapshot", SNAPSHOT_NAME)
                .put("sha256", snapshotHash)
                .put("createdAtEpochMs", System.currentTimeMillis())
                .toString().toByteArray()
        )

        try {
            applyPayload(payload, mode, resetDeviceBoundPreferences = true)
            writeAtomically(
                journalFile,
                JSONObject().put("state", "COMMITTED").put("sha256", snapshotHash).toString().toByteArray()
            )
            cleanupRecovery(recovery)
        } catch (error: Exception) {
            val rollbackResult = runCatching {
                val rollback = FullBackupArchive.decode(snapshotBytes.inputStream())
                applyPayload(rollback, FullBackupRestoreMode.REPLACE, resetDeviceBoundPreferences = false)
            }
            if (rollbackResult.isSuccess) {
                cleanupRecovery(recovery)
            } else {
                error.addSuppressed(rollbackResult.exceptionOrNull()!!)
                throw IllegalStateException("Restore failed and rollback remains pending in the recovery journal", error)
            }
            throw error
        }
    }

    fun recoverInterruptedRestore() {
        val recovery = recoveryDirectory()
        val journalFile = File(recovery, JOURNAL_NAME)
        val snapshotFile = File(recovery, SNAPSHOT_NAME)
        if (!journalFile.isFile) {
            cleanupRecovery(recovery)
            return
        }
        val journal = runCatching { JSONObject(journalFile.readText()) }.getOrNull()
        when (journal?.optString("state")) {
            "COMMITTED" -> cleanupRecovery(recovery)
            "APPLYING" -> {
                require(snapshotFile.isFile) { "Restore recovery snapshot is missing" }
                val bytes = snapshotFile.readBytes()
                require(sha256(bytes) == journal.optString("sha256")) {
                    "Restore recovery snapshot checksum mismatch"
                }
                val rollback = FullBackupArchive.decode(bytes.inputStream())
                applyPayload(rollback, FullBackupRestoreMode.REPLACE, resetDeviceBoundPreferences = false)
                cleanupRecovery(recovery)
            }
            else -> throw IllegalStateException("Restore recovery journal is invalid")
        }
    }

    private fun applyPayload(
        payload: FullBackupPayload,
        mode: FullBackupRestoreMode,
        resetDeviceBoundPreferences: Boolean
    ) {
        val root = app.filesDir.canonicalFile
        val rootPrefix = root.path + File.separator
        val filesToRestore = payload.files.filterNot { it.path == TAGS_PATH || it.path == APPS_PATH }
        val previousFiles = linkedMapOf<String, ByteArray?>()
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val previousPreferences = preferences.all
        val previousTagsAndApps = HailData.createTagBackup()
        val restoredTagState = FullBackupRestorePlan.tagState(previousTagsAndApps, payload.tagBackup, mode)

        try {
            if (mode == FullBackupRestoreMode.REPLACE && payload.selection.includeAdditionalFiles) {
                val existingFiles = root.walkTopDown().filter { it.isFile }.associateBy { file ->
                    file.canonicalFile.relativeTo(root).path.replace(File.separatorChar, '/')
                }
                FullBackupRestorePlan.filesToDelete(payload, mode, existingFiles.keys).forEach { relative ->
                    val file = existingFiles.getValue(relative)
                    val canonical = file.canonicalFile
                    require(canonical.path.startsWith(rootPrefix)) { "Unsafe replace target" }
                    previousFiles[relative] = canonical.readBytes()
                    check(canonical.delete()) { "Could not remove app data file: " + relative }
                }
            }

            filesToRestore.forEach { backupFile ->
                val target = File(root, backupFile.path).canonicalFile
                require(target.path.startsWith(rootPrefix)) { "Unsafe restore path" }
                if (backupFile.path !in previousFiles) {
                    previousFiles[backupFile.path] = target.takeIf { it.isFile }?.readBytes()
                }
                writeAtomically(target, backupFile.bytes)
            }

            val editor = preferences.edit()
            FullBackupRestorePlan.preferencesToRemove(payload, mode, preferences.all.keys).forEach(editor::remove)
            payload.preferences.forEach { (key, value) -> editor.putTyped(key, value) }
            if (resetDeviceBoundPreferences) {
                // Device-bound permissions and Keystore-backed biometric state cannot be restored.
                editor.putString(HailData.WORKING_MODE, HailData.MODE_DEFAULT)
                editor.putBoolean(HailData.BIOMETRIC_LOGIN, false)
                editor.putBoolean(HailData.AUTO_FREEZE_AFTER_LOCK, false)
                // Keep definitions portable, but never reactivate automation/profile state on import.
                editor.putBoolean(HailData.RULE_ENGINE_ENABLED, false)
                editor.remove(HailData.ACTIVE_PROFILE_ID)
            }
            check(editor.commit()) { "Could not save restored preferences" }

            HailData.restoreFullBackup(restoredTagState)
        } catch (error: Exception) {
            previousFiles.forEach { (path, bytes) ->
                runCatching {
                    val target = File(root, path).canonicalFile
                    if (bytes == null) target.delete() else writeAtomically(target, bytes)
                }
            }
            val rollbackKeys = payload.preferences.keys +
                if (mode == FullBackupRestoreMode.REPLACE) {
                    previousPreferences.keys.filter { FullBackupArchive.isAllowedPreferenceKey(it) }.toSet()
                } else emptySet()
            rollbackPreferences(preferences, previousPreferences, rollbackKeys)
            runCatching { HailData.restoreFullBackup(previousTagsAndApps) }
            throw error
        }
    }

    private fun rollbackPreferences(
        preferences: SharedPreferences,
        previous: Map<String, *>,
        restoredKeys: Set<String>
    ) {
        val editor = preferences.edit()
        val affectedKeys = restoredKeys + setOf(
            HailData.WORKING_MODE,
            HailData.BIOMETRIC_LOGIN,
            HailData.AUTO_FREEZE_AFTER_LOCK,
            HailData.RULE_ENGINE_ENABLED,
            HailData.ACTIVE_PROFILE_ID
        )
        affectedKeys.forEach { key ->
            val value = previous[key]
            if (value == null) editor.remove(key) else editor.putTyped(key, value)
        }
        editor.commit()
    }

    private fun SharedPreferences.Editor.putTyped(key: String, value: Any): SharedPreferences.Editor =
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> this
        }

    private fun recoveryDirectory() = File(app.noBackupFilesDir, RECOVERY_DIR)

    private fun cleanupRecovery(directory: File) {
        directory.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeAtomically(target: File, bytes: ByteArray) = AtomicFileStorage.write(target, bytes)
}
