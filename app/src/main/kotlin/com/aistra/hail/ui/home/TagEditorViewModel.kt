package com.aistra.hail.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.app.HailData
import com.aistra.hail.app.FullBackupStorage
import com.aistra.hail.app.TagAssignment
import com.aistra.hail.app.TagStateApp
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.UUID

internal data class TagEditorState(
    val session: TagEditSession? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val saved: Boolean = false,
    val launcherWarning: Boolean = false
)

/** Retains values and worker state, never a Fragment, Dialog, or completion callback. */
internal class TagEditorViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(TagEditorState())
    val state = mutableState.asStateFlow()
    private var initialized = false

    fun initialize(snapshot: String?, draftUnavailable: Boolean, interruptedSave: Boolean, savedUserId: Int?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                val session = runInterruptible(Dispatchers.IO) {
                    check(!draftUnavailable) { app.getString(R.string.state_unverified) }
                    if (savedUserId != null && savedUserId != HPackages.myUserId) throw TagEditConflictException()
                    if (snapshot != null) TagEditSession.importSnapshot(snapshot).also { it.requireUser(HPackages.myUserId) }
                    else AppDataAccess.access {
                        TagEditSession(HailData.tags.toList(), HailData.checkedList.filter {
                            it.userId == HPackages.myUserId
                        }.map { TagAssignment(it.packageName, it.tagIdList.toList()) }, HPackages.myUserId)
                    }
                }
                mutableState.value = TagEditorState(session, busy = false,
                    error = if (interruptedSave) app.getString(R.string.state_unverified) else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = TagEditorState(busy = false, error = errorMessage(error))
            }
        }
    }

    fun save() {
        val current = mutableState.value
        if (current.busy || current.saved) return
        val draft = current.session?.copyForCommit() ?: return
        mutableState.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val launcherWarning = runInterruptible(Dispatchers.IO) {
                    AppDataAccess.access { commit(draft) }
                }
                mutableState.value = current.copy(busy = false, saved = true, launcherWarning = launcherWarning)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = current.copy(busy = false, error = errorMessage(error))
            }
        }
    }

    fun consumeSaved() { mutableState.value = mutableState.value.copy(saved = false) }

    private companion object {
        fun errorMessage(error: Exception): String = when (error) {
            is TagEditForeignProfileConflictException -> app.getString(R.string.tag_editor_other_profile_conflict)
            is TagEditConflictException -> app.getString(R.string.tag_editor_conflict)
            else -> app.getString(R.string.tag_backup_error, HUI.operationError(error))
        }

        fun commit(draft: TagEditSession): Boolean {
            val previousTags = HailData.tags.toList()
            val currentRecords = HailData.checkedList.map {
                TagStateApp(it.packageName, it.pinned, it.whitelisted, it.userId, it.tagIdList)
            }
            val plan = draft.planCommitForUser(previousTags, currentRecords, HPackages.myUserId)
            val nextByIdentity = plan.apps.associateBy { it.packageName to it.userId }
            FullBackupStorage.editTagState {
                HailData.tags.clear()
                HailData.tags.addAll(plan.tags)
                HailData.checkedList.removeAll { (it.packageName to it.userId) !in nextByIdentity }
                HailData.checkedList.filter { it.userId == HPackages.myUserId }.forEach { record ->
                    record.tagIdList.clear()
                    record.tagIdList.addAll(nextByIdentity.getValue(record.packageName to record.userId).tagIds)
                }
                HailData.saveTags()
                HailData.saveApps()
            }
            var shortcutsUpdated = true
            plan.deletedTagNames.forEach { if (!HShortcuts.removeTagShortcuts(it)) shortcutsUpdated = false }
            // Stage renames under unique names so swaps cannot rewrite one another's shortcuts.
            val staged = plan.renamedTags.map { (old, next) ->
                Triple(old, "hail-tag-edit-${UUID.randomUUID()}", next)
            }
            staged.forEach { (old, temporary, _) ->
                if (!HShortcuts.refreshTagShortcuts(old, temporary)) shortcutsUpdated = false
            }
            staged.forEach { (_, temporary, next) ->
                if (!HShortcuts.refreshTagShortcuts(temporary, next)) shortcutsUpdated = false
            }
            return !shortcutsUpdated
        }
    }
}
