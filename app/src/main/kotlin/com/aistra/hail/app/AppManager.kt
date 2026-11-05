package com.aistra.hail.app

import android.content.Intent
import com.aistra.hail.BuildConfig
import com.aistra.hail.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

object AppManager {
    val lockScreen: Boolean
        get() = with(HailData.workingMode) {
            when {
                startsWith(HailData.OWNER) -> HPolicy.lockScreen
                startsWith(HailData.DHIZUKU) -> HDhizuku.lockScreen
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.lockScreen
                startsWith(HailData.SHIZUKU) -> HShizuku.lockScreen
                else -> false
            }
        }

    fun isAppFrozen(packageName: String): Boolean = with(HailData.workingMode) {
        FreezeModePolicy.isFrozen(this, HPackages.isAppDisabled(packageName),
            HPackages.isAppHidden(packageName), HPackages.isAppSuspended(packageName), HPackages.isAppStopped(packageName))
    }

    internal fun executeVerified(packageNames: Collection<String>, frozen: Boolean) =
        FreezeControllerFacade.execute(packageNames, frozen)

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String {
        val eligible = appInfo.filter { it.packageName != BuildConfig.APPLICATION_ID }
        if (eligible.isEmpty()) return "0"
        val results = executeVerified(eligible.map { it.packageName }, frozen)
        // The toast is a count of verified operations, not a permission diagnosis.
        // Failed/unverifiable operations retain their detailed journal entries.
        return com.aistra.hail.enhanced.domain.FreezeOperationReport.summarize(results).verified.toString()
    }

    internal fun recoverPendingFreezeOperations() {
        FreezeControllerFacade.recoverPending()
    }

    fun setAppFrozen(packageName: String, frozen: Boolean): Boolean {
        if (packageName == BuildConfig.APPLICATION_ID) return false
        val result = executeVerified(listOf(packageName), frozen).singleOrNull()
        return result?.outcome == com.aistra.hail.enhanced.domain.FreezeOutcome.SUCCESS &&
            result.stateVerified
    }

    fun uninstallApp(packageName: String): Boolean = with(HailData.workingMode) {
        if (
            (startsWith(HailData.OWNER) && HPolicy.uninstallApp(packageName))
            || (startsWith(HailData.DHIZUKU) && HDhizuku.uninstallApp(packageName))
            || ((startsWith(HailData.SU) || startsWith(HailData.JAILBREAK)) && HShell.uninstallApp(packageName))
            || (startsWith(HailData.SHIZUKU) && HShizuku.uninstallApp(packageName))
        )
            return true
        HUI.startActivity(Intent.ACTION_DELETE, HPackages.packageUri(packageName))
        false
    }

    fun reinstallApp(packageName: String): Boolean = with(HailData.workingMode) {
        when {
            startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.reinstallApp(packageName)
            startsWith(HailData.SHIZUKU) -> HShizuku.reinstallApp(packageName)
            else -> false
        }
    }

    suspend fun execute(command: String): Pair<Int, String?> = runInterruptible(Dispatchers.IO) {
        with(HailData.workingMode) {
            when {
                startsWith(HailData.SU) || startsWith(HailData.JAILBREAK) -> HShell.execute(command, true)
                startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
                else -> 0 to null
            }
        }
    }
}
