package com.aistra.hail.utils

/** Never let an ordinary app uninstall implicitly address every Android user. */
internal object UserScopedCommands {
    private fun target(packageName: String, userId: Int): String {
        require(userId >= 0) { "An explicit Android user is required" }
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(packageName)) { "Invalid package name" }
        return "--user $userId $packageName"
    }
    fun uninstall(packageName: String, userId: Int) = "pm uninstall " + target(packageName, userId)
    fun reinstall(packageName: String, userId: Int) = "pm install-existing " + target(packageName, userId)
}
