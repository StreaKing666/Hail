package com.aistra.hail.enhanced.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeControllerTest {
    private class FakeBackend : FreezeBackend {
        override val id = "fake"
        val states = mutableMapOf<String, Boolean>()
        var failSet = false
        var observable = true
        override fun supports(action: FreezeAction) = true
        override fun isFrozen(packageName: String, userId: Int): Boolean? =
            if (observable) states[packageName] ?: false else null
        override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
            if (failSet) return false
            states[packageName] = frozen
            return true
        }
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

    @Test fun doesNotClaimSuccessWhenStateCannotBeObserved() {
        val backend = FakeBackend().apply { observable = false }
        val controller = FreezeController(listOf(backend), InMemoryFreezeOperationJournal())
        val result = controller.execute(listOf("com.example.app"), FreezeAction.FREEZE, "fake", 0).single()
        assertEquals(FreezeOutcome.FAILED, result.outcome)
        assertFalse(result.stateVerified)
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
