package com.aistra.hail.utils

import android.os.Build
import androidx.annotation.RequiresApi

object HShell {
    // Foreground Android user can differ from this app's user (work profile/multiuser).
    private fun getCurrentUserId(): Int = android.os.Process.myUid() / 100_000

    private val userArg: String
        get() = "--user ${getCurrentUserId()}"

    fun execute(command: String, root: Boolean): Pair<Int, String?> {
        val result = BoundedProcessRunner.run(listOf(if (root) "su" else "sh"), command)
        val status = when {
            result.cancelled -> "\nCancelled"
            result.timedOut -> "\nCommand timed out; verify the actual package state before retrying"
            result.truncated -> "\n[Output truncated]"
            else -> ""
        }
        return result.exitCode to result.output + status
    }

    private fun execSU(command: String) = execute(command, true)

    val checkSU get() = execSU("whoami").first == 0

    val lockScreen get() = execSU("input keyevent KEYCODE_POWER").first == 0

    internal fun changeFrozenState(method: String, packageName: String, frozen: Boolean): Pair<Int, String?> {
        val command = when (method) {
            "stop" -> {
                require(frozen) { "Force-stop cannot be undone without launching the app" }
                "am force-stop $userArg $packageName"
            }
            "disable" -> "pm ${if (frozen) "disable" else "enable"} $userArg $packageName"
            "hide" -> "pm ${if (frozen) "hide" else "unhide"} $userArg $packageName"
            "suspend" -> "pm ${if (frozen) "suspend" else "unsuspend"} $userArg $packageName"
            else -> error("Unsupported freeze method")
        }
        return execSU(command)
    }

    fun forceStopApp(packageName: String): Boolean = changeFrozenState("stop", packageName, true).first == 0

    fun setAppDisabled(packageName: String, disabled: Boolean): Boolean =
        changeFrozenState("disable", packageName, disabled).first == 0

    fun setAppHidden(packageName: String, hidden: Boolean): Boolean =
        changeFrozenState("hide", packageName, hidden).first == 0

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean =
        changeFrozenState("suspend", packageName, suspended).first == 0

    fun uninstallApp(packageName: String): Boolean =
        execSU(UserScopedCommands.uninstall(packageName, getCurrentUserId())).first == 0

    fun reinstallApp(packageName: String): Boolean =
        execSU(UserScopedCommands.reinstall(packageName, getCurrentUserId())).first == 0

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean) = execSU(
        "appops set $userArg $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}"
    ).first == 0

    fun grantPermission(packageName: String, permission: String) =
        execSU("pm grant $userArg $packageName $permission").first == 0
}
