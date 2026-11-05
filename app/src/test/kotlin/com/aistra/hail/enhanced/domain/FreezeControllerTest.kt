package com.aistra.hail.enhanced.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class FreezeControllerTest {
    private class FakeBackend : FreezeBackend {
        override val id = "fake"
        val states = mutableMapOf<String, Boolean>()
        var failSet = false
        var observable = true
        var failureDetail: String? = null
        override fun failureReason(): String? = failureDetail
        override fun supports(action: FreezeAction) = true
        override fun isFrozen(packageName: String, userId: Int): Boolean? =
            if (observable) states[packageName] ?: false else null
        override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
            if (failSet) return false
            states[packageName] = frozen
            return true
        }
    }


    @Test fun waitsForDelayedObservationWithoutReplayingTheCommand() {
        var reads = 0
        var commands = 0
        val backend = object : FreezeBackend {
            override val id = "delayed"
            override fun supports(action: FreezeAction) = true
            override fun isFrozen(packageName: String, userId: Int): Boolean {
                reads++
                return commands > 0 && reads >= 4
            }
            override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
                commands++
                return true
            }
        }
        val journal = InMemoryFreezeOperationJournal()
        val result = FreezeController(listOf(backend), journal, sleeper = {})
            .execute(listOf("com.example.app"), FreezeAction.FREEZE, "delayed", 0).single()
        assertEquals(FreezeOutcome.SUCCESS, result.outcome)
        assertTrue(result.stateVerified)
        assertEquals(1, commands)
        assertEquals(FreezeJournalState.VERIFIED, journal.recent().single().state)
    }

    @Test fun interruptedObservationCancelsWithoutIssuingACommand() {
        var commands = 0
        val backend = object : FreezeBackend {
            override val id = "interrupted"
            override fun supports(action: FreezeAction) = true
            override fun isFrozen(packageName: String, userId: Int): Boolean? =
                throw InterruptedException("Cancelled")
            override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
                commands++
                return true
            }
        }
        try {
            assertThrows(InterruptedException::class.java) {
                FreezeController(listOf(backend), InMemoryFreezeOperationJournal())
                    .execute(listOf("com.example.app"), FreezeAction.FREEZE, "interrupted", 0)
            }
            assertEquals(0, commands)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }


    @Test fun staleAndUnobservableStatesStopAfterABoundedVerificationWindow() {
        for (observed in listOf(false, null)) {
            var commands = 0
            var reads = 0
            val delays = mutableListOf<Long>()
            val backend = object : FreezeBackend {
                override val id = "stale"
                override fun supports(action: FreezeAction) = true
                override fun isFrozen(packageName: String, userId: Int): Boolean? {
                    reads++
                    return observed
                }
                override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
                    commands++
                    return true
                }
            }
            val journal = InMemoryFreezeOperationJournal()
            val result = FreezeController(listOf(backend), journal, sleeper = { delays.add(it) })
                .execute(listOf("com.example.app"), FreezeAction.FREEZE, "stale", 0).single()
            assertEquals(FreezeOutcome.FAILED, result.outcome)
            assertFalse(result.stateVerified)
            assertEquals(1, commands)
            assertTrue(reads in 3..12)
            assertTrue(delays.isNotEmpty())
            assertTrue(delays.sum() <= 1_000L)
            assertEquals(if (observed == null) FreezeJournalState.UNVERIFIED else FreezeJournalState.FAILED,
                journal.recent().single().state)
        }
    }

    @Test fun cancellingVerificationPreservesPendingIntentAndDoesNotRunNextPackage() {
        var commands = 0
        val backend = object : FreezeBackend {
            override val id = "cancel"
            override fun supports(action: FreezeAction) = true
            override fun isFrozen(packageName: String, userId: Int): Boolean = false
            override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
                commands++
                return true
            }
        }
        val journal = InMemoryFreezeOperationJournal()
        try {
            assertThrows(InterruptedException::class.java) {
                FreezeController(listOf(backend), journal, sleeper = { throw InterruptedException("Cancelled") })
                    .execute(listOf("com.example.first", "com.example.second"), FreezeAction.FREEZE, "cancel", 0)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, commands)
            assertEquals("com.example.first", journal.pending().single().packageName)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun cancellationExceptionIsPropagated() {
        val backend = object : FreezeBackend {
            override val id = "cancel"
            override fun supports(action: FreezeAction): Boolean = throw CancellationException("Cancelled")
            override fun isFrozen(packageName: String, userId: Int): Boolean = error("must not observe")
            override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean = error("must not execute")
        }
        assertThrows(CancellationException::class.java) {
            FreezeController(listOf(backend), InMemoryFreezeOperationJournal(), sleeper = {})
                .execute(listOf("com.example.app"), FreezeAction.FREEZE, "cancel", 0)
        }
    }

    @Test fun mismatchedPendingRecoveryNeverReplaysACommand() {
        val backend = object : FreezeBackend {
            override val id = "recovery"
            override fun supports(action: FreezeAction): Boolean = true
            override fun isFrozen(packageName: String, userId: Int): Boolean? = null
            override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean = error("must not replay")
        }
        val journal = InMemoryFreezeOperationJournal()
        journal.appendPending(FreezeJournalEntry("pending", "com.example.app", 0, "recovery",
            FreezeAction.UNFREEZE, false, 1L, FreezeJournalState.PENDING))
        val result = FreezeController(listOf(backend), journal, sleeper = {}).recoverPending().single()
        assertEquals(FreezeOutcome.FAILED, result.outcome)
        assertFalse(result.stateVerified)
        assertTrue(journal.pending().isEmpty())
    }



    @Test fun shellDiagnosticsExposeKnownFailuresWithoutRetainingArbitraryOutput() {
        assertEquals("Shell command failed (exit 1): Root access denied",
            FreezeFailureDiagnostics.shell(1, "su: permission denied\nsecret-token=abc123"))
        assertEquals("Shell command failed (exit 124): Command timed out",
            FreezeFailureDiagnostics.shell(124, "Command timed out; arbitrary secret"))
        assertEquals("Shell command failed (exit 124): Command timed out",
            FreezeFailureDiagnostics.shell(124, "x".repeat(40_000) + "\nCommand timed out"))
        assertEquals("Shell command failed (exit 9)",
            FreezeFailureDiagnostics.shell(9, "echo private command; token=do-not-store"))
        assertEquals("Privileged command failed (SecurityException): Required permission: android.permission.CHANGE_COMPONENT_ENABLED_STATE",
            FreezeFailureDiagnostics.privileged(SecurityException(
                "Missing android.permission.CHANGE_COMPONENT_ENABLED_STATE token=do-not-store")))
    }

    @Test fun commandFailureDiagnosticReachesTheResultAndJournalAfterVerification() {
        val backend = FakeBackend().apply {
            failSet = true
            failureDetail = "Shell command failed (exit 1): Root permission denied"
        }
        val journal = InMemoryFreezeOperationJournal()
        val result = FreezeController(listOf(backend), journal, sleeper = {})
            .execute(listOf("com.example.app"), FreezeAction.FREEZE, "fake", 0).single()
        assertEquals(FreezeOutcome.FAILED, result.outcome)
        assertFalse(result.stateVerified)
        assertEquals(backend.failureDetail, result.reason)
        assertEquals(result.reason, journal.recent().single().reason)
    }

    @Test fun verifiedStateSucceedsEvenWhenTheCommandReturnedFailure() {
        var commands = 0
        val backend = object : FreezeBackend {
            override val id = "false-result"
            override fun failureReason(): String = "Shell command failed (exit 1): Permission denied"
            override fun supports(action: FreezeAction) = true
            override fun isFrozen(packageName: String, userId: Int): Boolean = commands > 0
            override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
                commands++
                return false
            }
        }
        val journal = InMemoryFreezeOperationJournal()
        val result = FreezeController(listOf(backend), journal, sleeper = {})
            .execute(listOf("com.example.app"), FreezeAction.FREEZE, backend.id, 0).single()
        assertEquals(FreezeOutcome.SUCCESS, result.outcome)
        assertTrue(result.stateVerified)
        assertEquals(1, commands)
        assertEquals(FreezeJournalState.VERIFIED, journal.recent().single().state)
    }

    @Test fun commandFailureWithoutDiagnosticRetainsGenericFallback() {
        val backend = FakeBackend().apply { failSet = true }
        val journal = InMemoryFreezeOperationJournal()
        val result = FreezeController(listOf(backend), journal, sleeper = {})
            .execute(listOf("com.example.app"), FreezeAction.FREEZE, "fake", 0).single()
        assertEquals(FreezeOutcome.FAILED, result.outcome)
        assertEquals("Observed state does not match the requested state", result.reason)
        assertEquals(result.reason, journal.recent().single().reason)
    }

    @Test fun separateControllersKeepBothJournalEntriesAtTheSameTimestamp() {
        val backend = FakeBackend()
        val journal = InMemoryFreezeOperationJournal()
        FreezeController(listOf(backend), journal, { 10L })
            .execute(listOf("com.example.app"), FreezeAction.FREEZE, "fake", 0)
        FreezeController(listOf(backend), journal, { 10L })
            .execute(listOf("com.example.app"), FreezeAction.UNFREEZE, "fake", 0)
        assertEquals(2, journal.recent().size)
        assertEquals(2, journal.recent().map { it.id }.toSet().size)
    }

    @Test fun verifiesStateAfterCommand() {
        val backend = FakeBackend()
        val controller = FreezeController(listOf(backend), InMemoryFreezeOperationJournal(), { 10L })
        val result = controller.execute(listOf("com.example.app"), FreezeAction.FREEZE, "fake", 0).single()
        assertEquals(FreezeOutcome.SUCCESS, result.outcome)
        assertTrue(result.stateVerified)
    }

    @Test fun protectsApplicationFromSelfFreeze() {
        val backend = FakeBackend()
        val controller = FreezeController(listOf(backend), InMemoryFreezeOperationJournal())
        val result = controller.execute(
            listOf("com.example.app"), FreezeAction.FREEZE, "fake", 0, setOf("com.example.app")
        ).single()
        assertEquals(FreezeOutcome.SKIPPED, result.outcome)
        assertFalse(result.stateVerified)
    }

    @Test fun doesNotClaimFreezeOrThawSuccessWhenStateCannotBeObserved() {
        for (action in FreezeAction.entries) {
            val backend = FakeBackend().apply { observable = false }
            val journal = InMemoryFreezeOperationJournal()
            val controller = FreezeController(listOf(backend), journal, sleeper = {})
            val result = controller.execute(listOf("com.example.app"), action, "fake", 0).single()
            assertEquals(FreezeOutcome.FAILED, result.outcome)
            assertFalse(result.stateVerified)
            assertEquals(FreezeJournalState.UNVERIFIED, journal.recent().single().state)
        }
    }

    @Test fun recoversPendingJournalWithoutReplayingCommand() {
        val backend = FakeBackend()
        val journal = InMemoryFreezeOperationJournal()
        journal.appendPending(
            FreezeJournalEntry(
                "pending", "com.example.app", 0, "fake", FreezeAction.FREEZE,
                true, 1L, FreezeJournalState.PENDING
            )
        )
        backend.states["com.example.app"] = true
        val controller = FreezeController(listOf(backend), journal)
        val result = controller.recoverPending().single()
        assertEquals(FreezeOutcome.SUCCESS, result.outcome)
        assertTrue(result.stateVerified)
        assertTrue(journal.pending().isEmpty())
    }

    @Test fun rejectsUnsupportedBackendWithoutCallingIt() {
        val backend = object : FreezeBackend {
            override val id = "fake"
            override fun supports(action: FreezeAction) = false
            override fun isFrozen(packageName: String, userId: Int): Boolean? = false
            override fun setFrozen(packageName: String, userId: Int, frozen: Boolean) = error("must not execute")
        }
        val result = FreezeController(listOf(backend), InMemoryFreezeOperationJournal())
            .execute(listOf("com.example.app"), FreezeAction.FREEZE, "fake", 0).single()
        assertEquals(FreezeOutcome.SKIPPED, result.outcome)
    }
}
