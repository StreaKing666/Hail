package com.aistra.hail.app

import android.content.pm.ApplicationInfo
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HPackages

class AppInfo(
    val packageName: String,
    var pinned: Boolean = false,
    var whitelisted: Boolean = false,
    val tagIdList: MutableList<Int> = mutableListOf(0),
    val userId: Int = android.os.Process.myUid() / 100_000
) {
    enum class State { NOT_FOUND, UNFROZEN, FROZEN, UNKNOWN }

    val applicationInfo: ApplicationInfo? get() =
        if (userId == HPackages.myUserId) HPackages.getApplicationInfoOrNull(packageName) else null
    val name get() = applicationInfo?.loadLabel(app.packageManager) ?: packageName
    val state: State get() = stateFor(applicationInfo)

    /** Classify the same snapshot that the row uses for its icon and label. */
    internal fun stateFor(info: ApplicationInfo?): State {
        val currentUser = userId == HPackages.myUserId
        val observed = if (currentUser && info != null) info.observeFrozenState() else null
        return State.valueOf(FreezePresentationPolicy.classify(currentUser, info != null, observed).name)
    }

    override fun equals(other: Any?): Boolean = other is AppInfo && other.packageName == packageName && other.userId == userId
    override fun hashCode(): Int = 31 * packageName.hashCode() + userId
}

/** Public flags and optional hidden flags must describe one package-manager snapshot. */
internal fun ApplicationInfo.observeFrozenState(mode: String = HailData.workingMode): Boolean? {
    if (uid / 100_000 != HPackages.myUserId) return null
    val observation = HPackages.getFreezeState(this)
    return FreezeModePolicy.isFrozen(mode, observation.disabled, observation.hidden,
        observation.suspended, observation.stopped)
}
