package com.aistra.hail.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.FreezeRuleStore
import com.aistra.hail.enhanced.domain.FreezeOperationReport
import com.aistra.hail.enhanced.domain.RuleEngine
import com.aistra.hail.enhanced.domain.RuleEvent
import com.aistra.hail.enhanced.domain.RuleTrigger

internal class RuleEngineWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = FreezeRuleStore(applicationContext)
        if (!store.isEnabled) return Result.success()
        val trigger = runCatching {
            RuleTrigger.valueOf(inputData.getString(KEY_TRIGGER) ?: "")
        }.getOrNull() ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()

        val evaluation = runCatching {
            RuleEngine.evaluate(
                rules = store.getRules(),
                event = RuleEvent(eventId, trigger),
                nowEpochMs = System.currentTimeMillis(),
                state = store.getExecutionState(),
                protectedPackages = setOf(applicationContext.packageName)
            )
        }.getOrElse {
            runCatching { store.appendAudit("Rule evaluation failed: " + (it.message ?: it.javaClass.simpleName)) }
            return Result.failure()
        }

        var failed = false
        evaluation.decisions.filter { it.status.name == "READY" }.forEach { decision ->
            val results = AppManager.executeVerified(
                decision.packages,
                frozen = decision.action.name == "FREEZE"
            )
            val summary = FreezeOperationReport.summarize(results)
            store.appendAudit(
                decision.ruleId + " " + decision.action.name + ": " +
                    summary.verified + "/" + summary.total + " verified"
            )
            if (summary.incomplete) failed = true
        }

        if (failed) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            store.appendAudit("Event " + eventId + " stopped after " + MAX_ATTEMPTS + " attempts")
            store.saveExecutionState(evaluation.nextState)
            return Result.failure()
        }

        // Commit idempotency only after the requested package states have been verified.
        // Replaying a partial batch is safe: the controller verifies already-applied states.
        store.saveExecutionState(evaluation.nextState)
        return Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val KEY_EVENT_ID = "event_id"
        private const val MAX_ATTEMPTS = 5
    }
}
