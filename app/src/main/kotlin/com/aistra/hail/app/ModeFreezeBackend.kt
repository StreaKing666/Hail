package com.aistra.hail.app

import com.aistra.hail.BuildConfig
import com.aistra.hail.enhanced.domain.FreezeAction
import com.aistra.hail.enhanced.domain.FreezeBackend
import com.aistra.hail.utils.HDhizuku
import com.aistra.hail.utils.HIsland
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HShell
import com.aistra.hail.utils.HShizuku

/**
 * Adapter for the currently selected Hail backend. It captures the mode so an operation cannot
 * silently switch backend halfway through a batch.
 */
internal class ModeFreezeBackend(override val id: String) : FreezeBackend {
    override fun supports(action: FreezeAction): Boolean {
        if (id !in HailData.WORKING_MODE_VALUES || id == HailData.MODE_DEFAULT) return false
        // Force-stop is a one-way "stopped" state. Clearing it requires launching the app;
        // never report a no-op as a successful unfreeze.
        if (id.endsWith(HailData.STOP) && action == FreezeAction.UNFREEZE) return false
        return true
    }

    override fun isFrozen(packageName: String, userId: Int): Boolean? {
        if (userId != currentUserId() || HPackages.getApplicationInfoOrNull(packageName) == null) return null
        return FreezeModePolicy.isFrozen(id, HPackages.isAppDisabled(packageName),
            HPackages.isAppHidden(packageName), HPackages.isAppSuspended(packageName),
            HPackages.isAppStopped(packageName))
    }

    override fun setFrozen(packageName: String, userId: Int, frozen: Boolean): Boolean {
        if (userId != currentUserId() || packageName == BuildConfig.APPLICATION_ID) return false
        if (!supports(if (frozen) FreezeAction.FREEZE else FreezeAction.UNFREEZE)) return false
        if (!frozen) {
            val modes = FreezeModePolicy.thawModes(id, HPackages.isAppDisabled(packageName),
                HPackages.isAppHidden(packageName), HPackages.isAppSuspended(packageName), HailData.WORKING_MODE_VALUES)
            var accepted = true
            modes.forEach { mode ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Operation cancelled")
                if (!setUsingMode(mode, packageName, false)) accepted = false
            }
            return accepted
        }
        return setUsingMode(id, packageName, true)
    }

    private fun setUsingMode(mode: String, packageName: String, frozen: Boolean): Boolean = when (mode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_OWNER_SUSPEND -> HPolicy.setAppSuspended(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_SU_STOP, HailData.MODE_SHIZUKU_STOP, HailData.MODE_PRIVAPP_STOP ->
                frozen && when (mode) {
                    HailData.MODE_SU_STOP -> HShell.forceStopApp(packageName)
                    HailData.MODE_SHIZUKU_STOP -> HShizuku.forceStopApp(packageName)
                    else -> HPackages.forceStopApp(packageName)
                }
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen)
            HailData.MODE_SHIZUKU_DISABLE -> HShizuku.setAppDisabled(packageName, frozen)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen)
            HailData.MODE_JAILBREAK_DISABLE, HailData.MODE_PRIVAPP_DISABLE ->
                HPackages.setAppDisabled(packageName, frozen)
            else -> false
        }

    private fun currentUserId(): Int = android.os.Process.myUid() / 100_000
}
