package com.aistra.hail.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistra.hail.app.FreezeProfileApplyResult
import com.aistra.hail.enhanced.domain.FreezeJournalEntry
import com.aistra.hail.enhanced.domain.FreezeProfile
import com.aistra.hail.enhanced.domain.FreezeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface EnhancedResult {
    data class Profiles(val values: List<FreezeProfile>) : EnhancedResult
    data class Rules(val values: List<FreezeRule>) : EnhancedResult
    data class Journal(val values: List<FreezeJournalEntry>) : EnhancedResult
    data class ProfileDraft(val value: FreezeProfile) : EnhancedResult
    data class Applied(val value: FreezeProfileApplyResult, val confirmed: Boolean = false) : EnhancedResult
    data class Saved(val profile: FreezeProfile? = null) : EnhancedResult
    data object Changed : EnhancedResult
    data object Restored : EnhancedResult
}

internal data class EnhancedOperation(val id: Long = 0, val busy: Boolean = false,
    val result: EnhancedResult? = null, val error: String? = null)

/** Retains a single operation across rotation; never retains an Activity/View callback. */
internal class EnhancedOperationsViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(EnhancedOperation())
    val state = mutableState.asStateFlow()
    fun run(operation: () -> EnhancedResult) {
        if (mutableState.value.busy) return
        val id = mutableState.value.id + 1
        mutableState.value = EnhancedOperation(id, busy = true)
        viewModelScope.launch {
            try {
                val value = runInterruptible(Dispatchers.IO) { operation() }
                mutableState.value = EnhancedOperation(id, result = value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("HailOperation", "Operation failed: " + error.javaClass.simpleName)
                mutableState.value = EnhancedOperation(id, error = error.message ?: error.javaClass.simpleName)
            }
        }
    }
    fun consume(id: Long) {
        if (mutableState.value.id == id && !mutableState.value.busy) mutableState.value = EnhancedOperation(id)
    }
}
