package com.aistra.hail.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.services.AutoFreezeService
import com.aistra.hail.utils.HSystem

class AutoFreezeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        AppDataAccess.access { freezeNow() }
    } catch (error: Exception) {
        android.util.Log.e("HailAutoFreeze", "Job failed: " + error.javaClass.simpleName)
        if (runAttemptCount < 4) Result.retry() else Result.failure()
    }

    private fun freezeNow(): Result {
        if (inputData.getBoolean(HailData.ACTION_LOCK, true) && !HailData.autoFreezeAfterLock) return Result.success()
        if ((inputData.getBoolean(HailData.ACTION_LOCK, true)
                    && HSystem.isInteractive(applicationContext))
            || isSkipWhileCharging(applicationContext)
        ) return Result.success() // Not stopping the AutoFreezeService here. The worker will run at some point. Then we'll stop the Service
        val checkedList = HailData.checkedList.filter { !isSkipApp(applicationContext, it) }
        val results = AppManager.executeVerified(checkedList.map { it.packageName }, true)
        val summary = com.aistra.hail.enhanced.domain.FreezeOperationReport.summarize(results)
        return if (summary.incomplete) {
            Result.failure()
        } else {
            runCatching { app.setAutoFreezeService() }
            Result.success()
        }
    }

    private fun isSkipWhileCharging(context: Context): Boolean =
        HailData.skipWhileCharging && HSystem.isCharging(context)

    private fun isSkipApp(context: Context, appInfo: AppInfo): Boolean =
        AppManager.isAppFrozen(appInfo.packageName) || (HailData.skipForegroundApp && HSystem.isForegroundApp(
            context, appInfo.packageName
        )) || (HailData.skipNotifyingApp && AutoFreezeService.instance.activeNotifications.any { it.packageName == appInfo.packageName }) || appInfo.whitelisted
}