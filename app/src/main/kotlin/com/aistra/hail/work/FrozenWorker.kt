package com.aistra.hail.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.app.RecoveryBlockedException
import com.aistra.hail.app.AppDataAccess

class FrozenWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val packageName = inputData.getString(HailData.KEY_PACKAGE)
        if (packageName.isNullOrBlank() || isStopped) Result.failure()
        else AppDataAccess.access {
            if (AppManager.setAppFrozen(packageName, inputData.getBoolean(HailData.KEY_FROZEN, true)) && !isStopped)
                Result.success() else Result.failure()
        }
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    } catch (_: RecoveryBlockedException) {
        // Startup recovery has not changed this job's package yet; retry is bounded.
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    } catch (error: Exception) {
        android.util.Log.e("HailDeferred", "Job failed: " + error.javaClass.simpleName)
        Result.failure()
    }
}
