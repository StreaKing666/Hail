package com.aistra.hail.app

import com.aistra.hail.HailApp.Companion.app
import java.io.File

/** Two-file tag edits retain a private, exact in-memory snapshot until commit or rollback. */
internal object TagStateTransaction {
    private fun journal() = RestoreJournal(File(app.noBackupFilesDir, "hail-tag-edit"))

    fun execute(change: () -> Unit) = AppDataAccess.access {
        val state = TagStateSnapshot(
            HailData.tags.map { TagStateTag(it.first, it.second) },
            HailData.checkedList.map {
                TagStateApp(it.packageName, it.pinned, it.whitelisted, it.userId, it.tagIdList)
            }
        )
        val bytes = TagStateSnapshotCodec.encode(state)
        try {
            journal().execute(bytes, apply = change, rollback = ::restore)
        } catch (error: RecoveryRequiredException) {
            AppDataAccess.requireRecovery()
            throw error
        }
    }

    /** Called inside startup's AppDataAccess.recover gate, including after a failed rollback. */
    fun recover() {
        try {
            journal().recover(::restore)
        } catch (error: Exception) {
            AppDataAccess.requireRecovery()
            throw error
        }
    }

    private fun restore(bytes: ByteArray) {
        val state = TagStateSnapshotCodec.decode(bytes)
        val apps = state.apps.map {
            AppInfo(it.packageName, it.pinned, it.whitelisted, it.tagIds.toMutableList(), it.userId)
        }
        HailData.replaceTagState(state.tags.map { it.name to it.id }, apps)
        HailData.saveTags()
        HailData.saveApps()
    }
}
