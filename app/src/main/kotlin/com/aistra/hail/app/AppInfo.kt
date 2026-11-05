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
    enum class State { NOT_FOUND, UNFROZEN, FROZEN }

    val applicationInfo: ApplicationInfo? get() =
        if (userId == HPackages.myUserId) HPackages.getApplicationInfoOrNull(packageName) else null
    val name get() = applicationInfo?.loadLabel(app.packageManager) ?: packageName
    val state
        get() = when {
            applicationInfo == null -> State.NOT_FOUND
            AppManager.isAppFrozen(packageName) -> State.FROZEN
            else -> State.UNFROZEN
        }

    override fun equals(other: Any?): Boolean = other is AppInfo && other.packageName == packageName && other.userId == userId
    override fun hashCode(): Int = 31 * packageName.hashCode() + userId
}
