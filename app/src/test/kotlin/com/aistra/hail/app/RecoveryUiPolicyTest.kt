package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class RecoveryUiPolicyTest {
    @Test fun pendingStartupRecreatesOnceAfterRecoveryBeforeAuthenticating() {
        val policy = RecoveryUiPolicy(RecoveryStatus.CHECKING)
        assertEquals(RecoveryUiAction.PROGRESS, policy.onStatus(RecoveryStatus.CHECKING))
        assertEquals(RecoveryUiAction.RECREATE, policy.onStatus(RecoveryStatus.READY))
        assertEquals(RecoveryUiAction.NONE, policy.onStatus(RecoveryStatus.READY))
    }
    @Test fun readyStartupInitializesOnceWithoutLosingRestoredFragmentContainers() {
        val policy = RecoveryUiPolicy(RecoveryStatus.READY)
        assertEquals(RecoveryUiAction.INITIALIZE, policy.onStatus(RecoveryStatus.READY))
        assertEquals(RecoveryUiAction.NONE, policy.onStatus(RecoveryStatus.READY))
    }
    @Test fun retryAfterManualRecoveryFailureRecreatesAndDoesNotReplayOperations() {
        val policy = RecoveryUiPolicy(RecoveryStatus.READY)
        policy.onStatus(RecoveryStatus.READY)
        assertEquals(RecoveryUiAction.FAILURE, policy.onStatus(RecoveryStatus.FAILED))
        assertEquals(RecoveryUiAction.PROGRESS, policy.onStatus(RecoveryStatus.CHECKING))
        assertEquals(RecoveryUiAction.RECREATE, policy.onStatus(RecoveryStatus.READY))
    }
}
