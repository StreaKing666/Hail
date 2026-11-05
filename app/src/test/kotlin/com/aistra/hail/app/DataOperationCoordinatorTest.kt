package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DataOperationCoordinatorTest {
    @Test fun busyUiMutationReturnsFailureBeforeChangingMemory() {
        val main = Thread.currentThread()
        val gate = DataOperationCoordinator { Thread.currentThread() == main }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Thread { gate.access { entered.countDown(); release.await(2, TimeUnit.SECONDS) } }
        var pinned = false
        worker.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            val result = gate.tryAccess { pinned = true }
            assertTrue(result.isFailure)
            assertFalse(pinned)
        } finally { release.countDown(); worker.join() }
        assertTrue(gate.tryAccess { pinned = true }.isSuccess)
        assertTrue(pinned)
    }
    @Test fun failedRecoveryBlocksNormalMutationsUntilSuccessfulRetry() {
        val gate = DataOperationCoordinator()
        assertThrows(IllegalStateException::class.java) { gate.recover { error("broken journal") } }
        assertTrue(gate.recoveryRequired)
        var mutated = false
        assertThrows(IllegalStateException::class.java) { gate.access { mutated = true } }
        assertFalse(mutated)
        gate.recover { }
        gate.access { mutated = true }
        assertTrue(mutated)
    }

    @Test fun transactionPreventsLostConcurrentUpdates() {
        val gate = DataOperationCoordinator()
        var value = 0
        val threads = List(4) { Thread { repeat(200) { gate.access { val old = value; Thread.yield(); value = old + 1 } } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(800, value)
    }

    @Test fun busyMainThreadDoesNotWaitAndNestedOperationsAreAllowed() {
        val main = Thread.currentThread()
        val gate = DataOperationCoordinator { Thread.currentThread() == main }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Thread { gate.access { entered.countDown(); release.await(2, TimeUnit.SECONDS) } }
        worker.start(); assertTrue(entered.await(1, TimeUnit.SECONDS))
        try { assertThrows(IllegalStateException::class.java) { gate.access { } } }
        finally { release.countDown(); worker.join() }
        gate.access { gate.access { } }
    }
}
