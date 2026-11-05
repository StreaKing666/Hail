package com.aistra.hail.app

internal object FreezeModePolicy {
    fun isFrozen(mode: String, disabled: Boolean?, hidden: Boolean?, suspended: Boolean?,
                 stopped: Boolean?): Boolean? {
        val observesStopped = mode.endsWith("_stop")
        if (disabled == true || hidden == true || suspended == true || (observesStopped && stopped == true)) return true
        if (disabled == null || hidden == null || suspended == null || (observesStopped && stopped == null)) return null
        return false
    }

    fun thawModes(mode: String, disabled: Boolean?, hidden: Boolean?, suspended: Boolean?,
                  supportedModes: Collection<String>): List<String> {
        val authority = mode.substringBefore('_')
        return listOf("disable" to disabled, "hide" to hidden, "suspend" to suspended)
            .filter { it.second == true }.map { (method, _) ->
                val selected = "${authority}_$method"
                check(selected in supportedModes) {
                    "This mode cannot undo $method; select a mode with the required permission"
                }
                selected
            }
    }
}
