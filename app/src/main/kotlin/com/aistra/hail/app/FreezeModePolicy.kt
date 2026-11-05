package com.aistra.hail.app

internal object FreezeModePolicy {
    fun isFrozen(mode: String, disabled: Boolean, hidden: Boolean, suspended: Boolean,
                 stopped: Boolean): Boolean = disabled || hidden || suspended || (mode.endsWith("_stop") && stopped)

    fun thawModes(mode: String, disabled: Boolean, hidden: Boolean, suspended: Boolean,
                  supportedModes: Collection<String>): List<String> {
        val authority = mode.substringBefore('_')
        return listOf("disable" to disabled, "hide" to hidden, "suspend" to suspended)
            .filter { it.second }.map { (method, _) ->
                val selected = "${authority}_$method"
                check(selected in supportedModes) {
                    "This mode cannot undo $method; select a mode with the required permission"
                }
                selected
            }
    }
}
