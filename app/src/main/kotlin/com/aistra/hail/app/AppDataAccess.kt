package com.aistra.hail.app

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object AppDataAccess {
    private val coordinator = DataOperationCoordinator { Looper.myLooper() == Looper.getMainLooper() }
        .apply { requireRecovery() }
    private val mutableStatus = MutableStateFlow(RecoveryStatus.CHECKING)
    val status = mutableStatus.asStateFlow()
    @Volatile var recoveryFailure: String? = null
        private set
    fun <T> access(block: () -> T): T = coordinator.access(block)
    fun <T> tryAccess(block: () -> T): Result<T> = coordinator.tryAccess(block)
    fun requireRecovery() {
        coordinator.requireRecovery()
        mutableStatus.value = RecoveryStatus.FAILED
    }
    fun recover(block: () -> Unit) {
        recoveryFailure = null
        mutableStatus.value = RecoveryStatus.CHECKING
        try {
            coordinator.recover(block)
            mutableStatus.value = RecoveryStatus.READY
        } catch (error: Exception) {
            recoveryFailure = if (error is StoredStateReadException)
                error.stateFile + " (" + (error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName) + ")"
            else error.javaClass.simpleName
            mutableStatus.value = RecoveryStatus.FAILED
            throw error
        }
    }
}
