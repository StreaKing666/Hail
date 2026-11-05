package com.aistra.hail.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException

data class PackageFreezeState(
    val disabled: Boolean,
    val hidden: Boolean?,
    val suspended: Boolean,
    val stopped: Boolean
)

object HPackages {
    val myUserId get() = android.os.Process.myUid() / 100_000

    fun packageUri(packageName: String) = "package:$packageName"

    @RequiresApi(Build.VERSION_CODES.N)
    fun packageUid(packageName: String) = if (HTarget.T) app.packageManager.getPackageUid(
        packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
    ) else app.packageManager.getPackageUid(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)

    fun getInstalledApplications(): List<ApplicationInfo> =
        if (HTarget.T) app.packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
        )
        else app.packageManager.getInstalledApplications(PackageManager.GET_UNINSTALLED_PACKAGES)

    fun getUnhiddenPackageInfoOrNull(
        packageName: String
    ) = runCatching {
        if (HTarget.T) app.packageManager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
        )
        else app.packageManager.getPackageInfo(packageName, PackageManager.GET_UNINSTALLED_PACKAGES)
    }.getOrNull()

    private fun getApplicationInfo(packageName: String): ApplicationInfo =
        if (HTarget.T) app.packageManager.getApplicationInfo(
            packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
        )
        else app.packageManager.getApplicationInfo(packageName, PackageManager.GET_UNINSTALLED_PACKAGES)

    fun getApplicationInfoOrNull(packageName: String): ApplicationInfo? =
        runCatching { getApplicationInfo(packageName) }.getOrNull()

    /** One package-manager observation; unavailable private flags remain unknown. */
    fun getFreezeState(packageName: String): PackageFreezeState? =
        getApplicationInfoOrNull(packageName)?.let(::getFreezeState)

    fun getFreezeState(info: ApplicationInfo): PackageFreezeState = PackageFreezeState(
        disabled = !info.enabled,
        hidden = isAppHidden(info),
        suspended = isAppSuspended(info),
        stopped = info.flags and ApplicationInfo.FLAG_STOPPED != 0
    )

    private val privateFlagsField: Field? by lazy { findPrivateFlagsField(false) }
    private val bypassPrivateFlagsField: Field? by lazy { findPrivateFlagsField(true) }

    @SuppressLint("PrivateApi")
    private fun findPrivateFlagsField(bypass: Boolean): Field? = try {
        val field = if (bypass) {
            if (HTarget.P) HiddenApiBypass.getInstanceFields(ApplicationInfo::class.java)
                .firstOrNull { it.name == "privateFlags" } else null
        } else ApplicationInfo::class.java.getDeclaredField("privateFlags")
        field?.apply { isAccessible = true }
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        // A platform that cannot initialize the optional bypass still supports public flags.
        null
    }

    private fun readPrivateFlags(field: Field?, info: ApplicationInfo): Int? = try {
        field?.getInt(info)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    fun privateFlagsOrNull(info: ApplicationInfo): Int? =
        readPrivateFlags(privateFlagsField, info) ?: readPrivateFlags(bypassPrivateFlagsField, info)

    fun isAppHidden(info: ApplicationInfo): Boolean? = privateFlagsOrNull(info)?.let { it and 1 != 0 }

    fun isAppSuspended(info: ApplicationInfo): Boolean =
        HTarget.N && info.flags and ApplicationInfo.FLAG_SUSPENDED != 0

    fun isAppDisabled(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.enabled?.not() ?: false

    fun isAppHidden(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.let(::isAppHidden) == true

    fun isAppStopped(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_STOPPED != 0 } ?: false

    fun isAppSuspended(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.let(::isAppSuspended) == true

    fun isAppUninstalled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_INSTALLED == 0 } ?: true

    fun isPrivilegedApp(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.let(::privateFlagsOrNull)?.let { it and 8 != 0 } == true

    fun canUninstallNormally(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.sourceDir?.startsWith("/data") ?: false

    fun forceStopAppResult(packageName: String): Result<Boolean> = privilegedCommand {
        val manager = checkNotNull(app.getSystemService<ActivityManager>()) { "Activity manager unavailable" }
        if (HTarget.P) HiddenApiBypass.invoke(manager::class.java, manager, "forceStopPackage", packageName)
        else manager::class.java.getMethod("forceStopPackage", String::class.java).invoke(manager, packageName)
        true
    }

    fun forceStopApp(packageName: String): Boolean = forceStopAppResult(packageName).getOrElse {
        HLog.e(it)
        false
    }

    fun setAppDisabledResult(packageName: String, disabled: Boolean): Result<Boolean> = privilegedCommand {
        getApplicationInfo(packageName)
        if (disabled) forceStopApp(packageName)
        val newState = if (disabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        app.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        !getApplicationInfo(packageName).enabled == disabled
    }

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean =
        setAppDisabledResult(packageName, disabled).getOrElse {
            HLog.e(it)
            false
        }

    private inline fun privilegedCommand(command: () -> Boolean): Result<Boolean> = try {
        Result.success(command())
    } catch (error: Exception) {
        // Reflection wraps the platform's SecurityException; retain the actual permission error.
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        when (cause) {
            is InterruptedException -> {
                Thread.currentThread().interrupt()
                throw cause
            }
            is CancellationException -> throw cause
            else -> Result.failure(cause)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean): Boolean = runCatching {
        app.getSystemService<AppOpsManager>()!!.let {
            HiddenApiBypass.invoke(
                it::class.java,
                it,
                "setMode",
                "android:run_any_in_background",
                packageUid(packageName),
                packageName,
                if (restricted) AppOpsManager.MODE_IGNORED else AppOpsManager.MODE_ALLOWED
            )
        }
        true
    }.getOrElse {
        HLog.e(it)
        false
    }
}
