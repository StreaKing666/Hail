package com.aistra.hail.app

import java.util.concurrent.locks.ReentrantLock

internal class DataBusyException : IllegalStateException("An operation is in progress; try again when it finishes")
internal class RecoveryBlockedException : IllegalStateException("Recovery is required before changing app data")

/** Lock order: this gate before any store/controller lock. Never suspend inside access. */
internal class DataOperationCoordinator(private val isMainThread: () -> Boolean = { false }) {
    private val lock = ReentrantLock(true)
    private var recoveryThread: Thread? = null
    @Volatile var recoveryRequired: Boolean = false
        private set

    fun <T> access(block: () -> T): T = locked {
        if (recoveryRequired && recoveryThread != Thread.currentThread()) throw RecoveryBlockedException()
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

    /** UI entry points acquire before touching memory and report contention as a result. */
    fun <T> tryAccess(block: () -> T): Result<T> = try {
        Result.success(access(block))
    } catch (error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        Result.failure(error)
    }

    private fun <T> locked(block: () -> T): T {
        if (isMainThread()) { if (!lock.tryLock()) throw DataBusyException() }
        else lock.lockInterruptibly()
        try { return block() } finally { lock.unlock() }
    }
}
