package com.aistra.hail.ui.main

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Values only: no Activity, Fragment, View or UI completion callback survives rotation. */
internal data class ManualResult(
    val messageRes: Int = 0,
    val value: String? = null,
    val intent: Intent? = null,
    val refresh: Boolean = true,
    val tagsChanged: Boolean = false,
    val tagEditorPackages: List<Pair<String, Int>>? = null,
    val tagEditorMultiselect: Boolean = false
)

internal data class ManualOperation(
    val id: Long = 0,
    val busy: Boolean = false,
    val result: ManualResult? = null,
    val error: String? = null
)

internal class ManualOperationsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ManualOperation())
    val state = mutableState.asStateFlow()

    fun run(operation: () -> ManualResult): Boolean {
        if (mutableState.value.busy || mutableState.value.result != null || mutableState.value.error != null) return false
        val id = mutableState.value.id + 1
        mutableState.value = ManualOperation(id, busy = true)
        viewModelScope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) { AppDataAccess.access(operation) }
                mutableState.value = ManualOperation(id, result = result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = ManualOperation(id, error = HUI.operationError(error))
            }
        }
        return true
    }

    fun consume(id: Long) {
        if (mutableState.value.id == id && !mutableState.value.busy) mutableState.value = ManualOperation(id)
    }

    /** Metadata-only edits: rollback both disk and memory if persistence is interrupted. */
    fun runData(operation: () -> ManualResult): Boolean = run {
        var result: ManualResult? = null
        com.aistra.hail.app.TagStateTransaction.execute { result = operation() }
        checkNotNull(result)
    }
}
