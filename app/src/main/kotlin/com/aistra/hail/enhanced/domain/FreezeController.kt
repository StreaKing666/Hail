package com.aistra.hail.enhanced.domain

import java.util.UUID
import java.util.concurrent.CancellationException

internal enum class FreezeJournalState { PENDING, VERIFIED, FAILED, UNVERIFIED }

internal data class FreezeJournalEntry(
    val id: String,
    val packageName: String,
    val userId: Int,
    val backendId: String,
    val action: FreezeAction,
    val targetFrozen: Boolean,
    val createdAtEpochMs: Long,
    val state: FreezeJournalState,
    val reason: String? = null
)

internal interface FreezeBackend {
    val id: String
    fun supports(action: FreezeAction): Boolean
    /** Null means that this backend cannot reliably observe the current state. */
    fun isFrozen(packageName: String, userId: Int): Boolean?
    fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean
    /** Optional diagnostic for the most recent setFrozen call on this backend instance. */
    fun failureReason(): String? = null
}

internal interface FreezeOperationJournal {
    fun appendPending(entry: FreezeJournalEntry)
    fun complete(entry: FreezeJournalEntry)
    fun pending(): List<FreezeJournalEntry>
    fun recent(limit: Int = 100): List<FreezeJournalEntry>
}

internal class InMemoryFreezeOperationJournal(
    private val maxEntries: Int = 200
) : FreezeOperationJournal {
    private val entries = mutableListOf<FreezeJournalEntry>()

    init {
        require(maxEntries in 1..10_000)
    }

    @Synchronized
    override fun appendPending(entry: FreezeJournalEntry) {
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
        trim()
    }

    @Synchronized
    override fun complete(entry: FreezeJournalEntry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) entries[index] = entry else entries.add(entry)
        trim()
    }

    @Synchronized
    override fun pending(): List<FreezeJournalEntry> =
        entries.filter { it.state == FreezeJournalState.PENDING }.toList()

    @Synchronized
    override fun recent(limit: Int): List<FreezeJournalEntry> {
        require(limit >= 0)
        return entries.takeLast(limit).asReversed()
    }

    private fun trim() {
        while (entries.size > maxEntries) entries.removeAt(0)
    }
}

/**
 * Serializes privileged state changes, records intent before execution and verifies the
 * observable package state afterwards. Recovery only reconciles journal entries; it never
 * replays privileged commands automatically.
 */
internal class FreezeController(
    backends: Collection<FreezeBackend>,
    private val journal: FreezeOperationJournal,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = Thread::sleep
) {
    private val backendsById = backends.associateBy { it.id }

    @Synchronized
    fun execute(
        packageNames: Collection<String>,
        action: FreezeAction,
        backendId: String,
        userId: Int,
        protectedPackages: Set<String> = emptySet()
    ): List<FreezeOperationResult> {
        val backend = backendsById[backendId]
        return packageNames.distinct().map { packageName ->
            executeOne(packageName, action, backendId, userId, protectedPackages, backend)
        }
    }

    @Synchronized
    fun recoverPending(): List<FreezeOperationResult> =
        journal.pending().map { entry ->
            val backend = backendsById[entry.backendId]
            val observed = safelyObserve { backend?.isFrozen(entry.packageName, entry.userId) }
            val recovered = observed == entry.targetFrozen
            val completed = entry.copy(
                state = if (recovered) FreezeJournalState.VERIFIED else FreezeJournalState.FAILED,
                reason = if (recovered) {
                    "Recovered from the journal; observed state already matches the request"
                } else {
                    "Operation was interrupted; no automatic state change was replayed"
                }
            )
            journal.complete(completed)
            FreezeOperationResult(
                packageName = entry.packageName,
                userId = entry.userId,
                action = entry.action,
                backendId = entry.backendId,
                outcome = if (recovered) FreezeOutcome.SUCCESS else FreezeOutcome.FAILED,
                stateVerified = recovered,
                reason = completed.reason
            )
        }

    fun recentOperations(limit: Int = 100): List<FreezeJournalEntry> = journal.recent(limit)

    private fun executeOne(
        packageName: String,
        action: FreezeAction,
        backendId: String,
        userId: Int,
        protectedPackages: Set<String>,
        backend: FreezeBackend?
    ): FreezeOperationResult {
        checkInterrupted()
        if (!PACKAGE_NAME.matches(packageName)) {
            return result(packageName, action, backendId, userId, FreezeOutcome.FAILED, false, "Invalid package name")
        }
        if (packageName in protectedPackages) {
            return result(packageName, action, backendId, userId, FreezeOutcome.SKIPPED, false, "Protected package")
        }
        if (backend == null) {
            return result(packageName, action, backendId, userId, FreezeOutcome.FAILED, false, "Backend is unavailable")
        }
        if (safelyObserve { backend.supports(action) } != true) {
            return result(packageName, action, backendId, userId, FreezeOutcome.SKIPPED, false, "Backend does not support this action")
        }

        val targetFrozen = action == FreezeAction.FREEZE
        val before = safelyObserve { backend.isFrozen(packageName, userId) }
        if (before == targetFrozen) {
            return result(packageName, action, backendId, userId, FreezeOutcome.SUCCESS, true, "Already in the requested state")
        }

        val id = UUID.randomUUID().toString()
        val pending = FreezeJournalEntry(
            id = id,
            packageName = packageName,
            userId = userId,
            backendId = backendId,
            action = action,
            targetFrozen = targetFrozen,
            createdAtEpochMs = now(),
            state = FreezeJournalState.PENDING
        )
        return try {
            journal.appendPending(pending)
            checkInterrupted()
            val accepted = backend.setFrozen(packageName, userId, targetFrozen)
            val commandFailure = if (accepted) null else safelyObserve { backend.failureReason() }
                ?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim()?.take(1024)?.takeIf { it.isNotEmpty() }
            val after = awaitObservedState(backend, packageName, userId, targetFrozen)
            val verified = after == targetFrozen
            val outcome = if (verified) FreezeOutcome.SUCCESS else FreezeOutcome.FAILED
            val reason = when {
                verified && !accepted -> "Backend returned failure, but the requested state was verified"
                verified -> null
                commandFailure != null -> commandFailure
                after == null -> "Backend command completed without a verifiable state"
                else -> "Observed state does not match the requested state"
            }
            journal.complete(
                pending.copy(
                    state = when {
                        verified -> FreezeJournalState.VERIFIED
                        after == null -> FreezeJournalState.UNVERIFIED
                        else -> FreezeJournalState.FAILED
                    },
                    reason = reason
                )
            )
            result(packageName, action, backendId, userId, outcome, verified, reason)
        } catch (error: Exception) {
            // A cancelled command may have changed state. Leave its intent pending so recovery
            // can reconcile the state without replaying it.
            rethrowCancellation(error)
            val reason = error.message ?: error.javaClass.simpleName
            safelyObserve {
                journal.complete(pending.copy(state = FreezeJournalState.FAILED, reason = reason))
            }
            result(packageName, action, backendId, userId, FreezeOutcome.FAILED, false, reason)
        }
    }

    private fun awaitObservedState(
        backend: FreezeBackend, packageName: String, userId: Int, targetFrozen: Boolean
    ): Boolean? {
        var observed = safelyObserve { backend.isFrozen(packageName, userId) }
        repeat(VERIFICATION_RETRIES) {
            if (observed == targetFrozen) return observed
            checkInterrupted()
            sleeper(VERIFICATION_INTERVAL_MS)
            observed = safelyObserve { backend.isFrozen(packageName, userId) }
        }
        return observed
    }

    private inline fun <T> safelyObserve(block: () -> T): T? = try {
        checkInterrupted()
        block()
    } catch (error: Exception) {
        rethrowCancellation(error)
        null
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Operation cancelled")
    }

    private fun rethrowCancellation(error: Exception) {
        when (error) {
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw error
            }
            is CancellationException -> throw error
        }
    }

    private fun result(
        packageName: String,
        action: FreezeAction,
        backendId: String,
        userId: Int,
        outcome: FreezeOutcome,
        verified: Boolean,
        reason: String?
    ) = FreezeOperationResult(packageName, userId, action, backendId, outcome, verified, reason)

    private companion object {
        const val VERIFICATION_RETRIES = 10
        const val VERIFICATION_INTERVAL_MS = 100L
        val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}

/** Persist recognized platform failure categories, never arbitrary shell output or command text. */
internal object FreezeFailureDiagnostics {
    private val permission = Regex("android\\.permission\\.[A-Z][A-Z0-9_]*")

    fun shell(exitCode: Int, output: String?): String {
        val text = output.orEmpty()
        // Runner status is appended after stdout, including when stdout was truncated.
        val detail = category(text.take(16_384) + "\n" + text.takeLast(16_384))
        return ("Shell command failed (exit $exitCode)" + if (detail == null) "" else ": $detail").take(1024)
    }

    fun privileged(error: Throwable): String {
        val type = error.javaClass.simpleName.take(80)
        val detail = category(error.message.orEmpty().take(32_768))
        return ("Privileged command failed ($type)" + if (detail == null) "" else ": $detail").take(1024)
    }

    private fun category(text: String): String? {
        val lower = text.lowercase(java.util.Locale.ROOT)
        val requiredPermission = permission.findAll(text).map { it.value }.distinct().take(4).toList()
        return when {
            "timed out" in lower || "timeout" in lower -> "Command timed out"
            "cancelled" in lower || "canceled" in lower -> "Command cancelled"
            requiredPermission.isNotEmpty() -> "Required permission: " + requiredPermission.joinToString(", ")
            "securityexception" in lower -> "SecurityException: operation denied"
            "root access denied" in lower || "root permission denied" in lower ||
                "su: permission denied" in lower -> "Root access denied"
            "permission denied" in lower || "permission denial" in lower -> "Permission denied"
            "not allowed" in lower || "access denied" in lower -> "Operation denied"
            "unknown command" in lower || "not supported" in lower || "unsupported" in lower -> "Unsupported command"
            "not found" in lower || "unknown package" in lower -> "Command or package not found"
            "illegalargumentexception" in lower || "invalid argument" in lower -> "Invalid argument"
            "unavailable" in lower -> "Required package or service unavailable"
            else -> null
        }
    }
}
