package com.aistra.hail.app

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class RecoveryStatus { CHECKING, READY, FAILED }

internal object AppDataAccess {
    private val coordinator = DataOperationCoordinator { Looper.myLooper() == Looper.getMainLooper() }
        .apply { requireRecovery() }
    private val mutableStatus = MutableStateFlow(RecoveryStatus.CHECKING)
    val status = mutableStatus.asStateFlow()
    fun <T> access(block: () -> T): T = coordinator.access(block)
    fun requireRecovery() {
        coordinator.requireRecovery()
        mutableStatus.value = RecoveryStatus.FAILED
    }
    fun recover(block: () -> Unit) {
        mutableStatus.value = RecoveryStatus.CHECKING
        try {
            coordinator.recover(block)
            mutableStatus.value = RecoveryStatus.READY
        } catch (error: Exception) {
            mutableStatus.value = RecoveryStatus.FAILED
            throw error
        }
    }
}
