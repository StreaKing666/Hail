package com.aistra.hail.app

internal enum class RecoveryStatus { CHECKING, READY, FAILED }
internal enum class RecoveryUiAction { PROGRESS, FAILURE, INITIALIZE, RECREATE, NONE }

/** Keep real Fragment containers present; hide their content until recovery/authentication. */
internal class RecoveryUiPolicy(initial: RecoveryStatus) {
    private var initialized = false
    private var needsRecreate = initial != RecoveryStatus.READY
    private var recreateRequested = false

    fun onStatus(status: RecoveryStatus): RecoveryUiAction = when (status) {
        RecoveryStatus.CHECKING -> { needsRecreate = true; RecoveryUiAction.PROGRESS }
        RecoveryStatus.FAILED -> { needsRecreate = true; RecoveryUiAction.FAILURE }
        RecoveryStatus.READY -> when {
            recreateRequested -> RecoveryUiAction.NONE
            needsRecreate -> { recreateRequested = true; RecoveryUiAction.RECREATE }
            !initialized -> { initialized = true; RecoveryUiAction.INITIALIZE }
            else -> RecoveryUiAction.NONE
        }
    }
}
