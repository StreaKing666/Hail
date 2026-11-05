package com.aistra.hail.app

import java.util.concurrent.locks.ReentrantLock

/** Lock order: this gate before any store/controller lock. Never suspend inside access. */
internal class DataOperationCoordinator(private val isMainThread: () -> Boolean = { false }) {
    private val lock = ReentrantLock(true)
    private var recoveryThread: Thread? = null
    @Volatile var recoveryRequired: Boolean = false
        private set

    fun <T> access(block: () -> T): T = locked {
        check(!recoveryRequired || recoveryThread == Thread.currentThread()) { "Recovery is required before changing app data" }
        block()
    }

    fun <T> recover(block: () -> T): T = locked {
        val previous = recoveryThread
        recoveryThread = Thread.currentThread()
        try {
            val result = block()
            recoveryRequired = false
            result
        } catch (error: Exception) {
            recoveryRequired = true
            throw error
        } finally {
            recoveryThread = previous
        }
    }

    fun requireRecovery() { recoveryRequired = true }

    private fun <T> locked(block: () -> T): T {
        if (isMainThread()) check(lock.tryLock()) { "An operation is in progress; try again when it finishes" }
        else lock.lockInterruptibly()
        try { return block() } finally { lock.unlock() }
    }
}
