package com.aistra.hail.enhanced.domain

/** Builds validated copies. Dialogs keep drafts private until the final Save. */
internal object EnhancedEditors {
    fun profile(original: FreezeProfile, name: String, states: Map<String, Boolean>,
                now: Long, protectedPackage: String): FreezeProfile = original.copy(
        name = name.trim(),
        packageStates = states.filterKeys { it != protectedPackage },
        updatedAtEpochMs = now
    ).also { FreezeProfileEngine.validate(listOf(it)) }

    fun rule(original: FreezeRule, name: String, packages: Set<String>,
             cooldownSeconds: String, protectedPackage: String): FreezeRule {
        val seconds = cooldownSeconds.trim().toLongOrNull()
        require(seconds != null && seconds in 0L..86_400L) { "Cooldown must be 0–86400 seconds" }
        return original.copy(name = name.trim(), packageNames = packages.filterTo(linkedSetOf()) {
            it != protectedPackage
        }, cooldownMillis = seconds * 1000).also { RuleEngine.validate(listOf(it)) }
    }
}

internal object ProfileShortcutIds {
    fun dynamic(id: String) = "hail-profile-$id"
    fun pinned(id: String) = "hail-profile-pin-$id"
    fun all(id: String) = listOf(dynamic(id), pinned(id))
}
