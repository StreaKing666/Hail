package com.aistra.hail.app

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.AtomicFileStorage
import java.io.File

internal object FullBackupStorage {
    private const val TAGS_PATH = "v1/tags.json"
    private const val APPS_PATH = "v1/apps.json"
    private const val RECOVERY_DIR = "hail-full-restore"

    fun createArchive(selection: FullBackupSelection = FullBackupSelection()): ByteArray = AppDataAccess.access {
        HailData.saveTags()
        HailData.saveApps()
        FullBackupArchive.encode(
            files = BackupFileReader.read(app.filesDir, selection),
            preferences = PreferenceManager.getDefaultSharedPreferences(app).all,
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            selection = selection
        )
    }

    /** Private rollback captures raw files and local preferences; portable export remains separate. */
    fun restore(payload: FullBackupPayload, mode: FullBackupRestoreMode = FullBackupRestoreMode.MERGE) = AppDataAccess.access {
        val state = TagStateSnapshot(HailData.tags.map { TagStateTag(it.first, it.second) },
            HailData.checkedList.map { TagStateApp(it.packageName, it.pinned, it.whitelisted, it.userId, it.tagIdList) })
        val exact = FullRecoverySnapshot(
            BackupFileReader.read(app.filesDir, requireStateFiles = false), state,
            PreferenceManager.getDefaultSharedPreferences(app).all.mapValues { requireNotNull(it.value) })
        val snapshot = FullRecoverySnapshotCodec.encode(exact)
        // Never mutate data until the private snapshot has passed its own recovery decoder.
        val decoded = FullRecoverySnapshotCodec.decode(snapshot)
        require(decoded.tagState == exact.tagState && decoded.preferences.keys == exact.preferences.keys &&
            exact.preferences.all { (key, value) ->
                val restored = decoded.preferences[key]
                if (value is Float) restored is Float && value.toRawBits() == restored.toRawBits()
                else value == restored
            }) { "Recovery snapshot cannot preserve the current state exactly" }
        try {
            RestoreJournal(recoveryDirectory()).execute(snapshot,
                apply = { applyPayload(payload, mode, resetDeviceBoundPreferences = true) },
                rollback = ::restoreSnapshot)
        } catch (error: RecoveryRequiredException) {
            AppDataAccess.requireRecovery()
            throw error
        }
    }

    fun recoverInterruptedRestore() {
        RestoreJournal(recoveryDirectory()).recover(::restoreSnapshot)
        TagStateTransaction.recover()
    }

    fun editTagState(change: () -> Unit) = TagStateTransaction.execute(change)

    private fun restoreSnapshot(bytes: ByteArray) {
        val exact = FullRecoverySnapshotCodec.decodeOrNull(bytes)
        if (exact == null) {
            // Canonical .13 journals contained portable archives. Keep their reader for upgrades.
            val payload = FullBackupArchive.decode(bytes.inputStream())
            HailData.replaceTagState(payload.tagBackup.tags.map { it.name to it.id }, payload.tagBackup.apps.map {
                AppInfo(it.packageName, it.pinned, it.whitelisted, it.tagIds.toMutableList())
            })
            applyPayload(payload, FullBackupRestoreMode.REPLACE, resetDeviceBoundPreferences = false)
            return
        }
        val root = app.filesDir.canonicalFile
        val paths = exact.files.map { it.path }.toSet()
        val memoryApps = exact.tagState.apps.map {
            AppInfo(it.packageName, it.pinned, it.whitelisted, it.tagIds.toMutableList(), it.userId)
        }
        HailData.replaceTagState(exact.tagState.tags.map { it.name to it.id }, memoryApps)
        currentFiles(root).filterKeys { it !in paths }.values.forEach {
            check(it.delete()) { "Could not remove a file created during restore" }
        }
        // A failed import may have replaced a prior file with a directory, or vice versa.
        root.walkBottomUp().filter { it.isDirectory && it != root }.forEach {
            require(it.canonicalFile == it.absoluteFile) { "Unsafe recovery directory" }
            if (it.list()?.isEmpty() == true) check(it.delete()) { "Could not remove an empty recovery directory" }
        }
        exact.files.forEach { writeAtomically(safeTarget(root, it.path), it.bytes) }
        val editor = PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
        exact.preferences.forEach { (key, value) -> editor.putTyped(key, value) }
        check(editor.commit()) { "Could not restore local preferences" }
        // Raw v1 files are already restored exactly; do not normalize or rewrite them here.
    }

    private fun applyPayload(payload: FullBackupPayload, mode: FullBackupRestoreMode, resetDeviceBoundPreferences: Boolean) {
        val root = app.filesDir.canonicalFile
        val restoredTagState = FullBackupRestorePlan.tagState(HailData.createTagBackup(), payload.tagBackup, mode)
        if (mode == FullBackupRestoreMode.REPLACE && payload.selection.includeAdditionalFiles) {
            val existing = currentFiles(root)
            FullBackupRestorePlan.filesToDelete(payload, mode, existing.keys).forEach { path ->
                check(existing.getValue(path).delete()) { "Could not remove app data file" }
            }
        }
        payload.files.filterNot { it.path == TAGS_PATH || it.path == APPS_PATH }.forEach {
            writeAtomically(safeTarget(root, it.path), it.bytes)
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(app)
        val editor = preferences.edit()
        FullBackupRestorePlan.preferencesToRemove(payload, mode, preferences.all.keys).forEach(editor::remove)
        payload.preferences.forEach { (key, value) -> editor.putTyped(key, value) }
        if (resetDeviceBoundPreferences) {
            editor.putString(HailData.WORKING_MODE, HailData.MODE_DEFAULT)
            editor.putBoolean(HailData.BIOMETRIC_LOGIN, false)
            editor.putBoolean(HailData.AUTO_FREEZE_AFTER_LOCK, false)
            editor.putBoolean(HailData.RULE_ENGINE_ENABLED, false)
            editor.remove(HailData.ACTIVE_PROFILE_ID)
        }
        check(editor.commit()) { "Could not save restored preferences" }
        HailData.restoreFullBackup(restoredTagState)
    }

    private fun currentFiles(root: File): Map<String, File> = root.walkTopDown().onEnter {
        require(it.canonicalFile == it.absoluteFile) { "Unsafe app data directory" }
        true
    }.filter { it.isFile }.associate { file ->
        require(file.canonicalFile == file.absoluteFile && file.canonicalPath.startsWith(root.path + File.separator)) {
            "Unsafe app data file"
        }
        file.relativeTo(root).invariantSeparatorsPath to file
    }

    private fun safeTarget(root: File, path: String): File {
        val target = File(root, path).absoluteFile
        require(target.canonicalFile == target && target.path.startsWith(root.path + File.separator)) { "Unsafe restore path" }
        return target
    }

    private fun SharedPreferences.Editor.putTyped(key: String, value: Any): SharedPreferences.Editor = when (value) {
        is String -> putString(key, value)
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Set<*> -> {
            require(value.all { it is String }) { "Invalid preference string set" }
            putStringSet(key, value.map { it as String }.toSet())
        }
        else -> throw IllegalArgumentException("Unsupported preference type")
    }

    private fun recoveryDirectory() = File(app.noBackupFilesDir, RECOVERY_DIR)
    private fun writeAtomically(target: File, bytes: ByteArray) = AtomicFileStorage.write(target, bytes)
}
