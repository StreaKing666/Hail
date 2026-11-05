package com.aistra.hail.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aistra.hail.enhanced.domain.RuleTrigger
import java.util.concurrent.TimeUnit

internal object RuleScheduler {
    fun enqueue(context: Context, trigger: RuleTrigger) {
        if (com.aistra.hail.app.AppDataAccess.status.value != com.aistra.hail.app.RecoveryStatus.READY) return
        val store = com.aistra.hail.app.FreezeRuleStore(context)
        if (!store.isEnabled) return
        val eventId = trigger.name + "-" + System.currentTimeMillis() / 1000L
        val request = OneTimeWorkRequestBuilder<RuleEngineWorker>()
            .setInputData(
                workDataOf(
                    RuleEngineWorker.KEY_TRIGGER to trigger.name,
                    RuleEngineWorker.KEY_EVENT_ID to eventId
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
            .build()

        // Coalesce repeated system broadcasts into at most one unfinished job per trigger.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "hail-rule-" + trigger.name,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
