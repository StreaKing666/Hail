package com.aistra.hail.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class BoundedProcessRunnerTest {
    @Test fun drainsLargeOutputBeforeWaitingAndBoundsCapturedBytes() {
        val result = BoundedProcessRunner.run(listOf("sh"),
            "head -c 2000000 /dev/zero; printf done", 5_000, 4096)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.toByteArray().size <= 4200)
        assertTrue(result.truncated)
    }

    @Test fun timesOutAndReapsNeverEndingProcess() {
        val start = System.nanoTime()
        val result = BoundedProcessRunner.run(listOf("sh"), "exec sleep 30", 150, 1024)
        assertEquals(124, result.exitCode)
        assertTrue(result.timedOut)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    @Test fun interruptionCancelsWithoutReportingSuccess() {
        val result = AtomicReference<ProcessExecution>()
        val worker = Thread {
            result.set(BoundedProcessRunner.run(listOf("sh"), "exec sleep 30", 10_000, 1024))
            assertTrue(Thread.currentThread().isInterrupted)
        }
        worker.start(); Thread.sleep(100); worker.interrupt(); worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals(130, result.get().exitCode)
        assertTrue(result.get().cancelled)
    }

    @Test fun preservesOutputAndNonzeroExit() {
        val result = BoundedProcessRunner.run(listOf("sh"), "printf failure >&2; exit 7", 2_000, 1024)
        assertEquals(7, result.exitCode)
        assertEquals("failure", result.output)
        assertFalse(result.timedOut)
    }
}
