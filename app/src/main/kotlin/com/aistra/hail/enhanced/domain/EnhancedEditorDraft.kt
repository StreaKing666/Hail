package com.aistra.hail.enhanced.domain

import org.json.JSONObject

/** JSON survives recreation, including temporarily invalid text. No save until validation. */
internal data class EnhancedEditorDraft(
    val profile: Boolean, val isNew: Boolean, val original: String,
    val name: String, val states: Map<String, Boolean>, val cooldown: String = "30",
    val enabled: Boolean = true, val trigger: RuleTrigger = RuleTrigger.SCREEN_OFF,
    val action: RuleAction = RuleAction.FREEZE, val search: String = ""
) {
    fun originalProfile(): FreezeProfile {
        val json = JSONObject(original)
        return FreezeProfile(json.getString("id"), json.getString("name"), readStates(json.getJSONObject("states")),
            json.getLong("created"), json.getLong("updated"))
    }
    fun originalRule(): FreezeRule {
        val json = JSONObject(original)
        return FreezeRule(json.getString("id"), json.getString("name"), json.getBoolean("enabled"),
            RuleTrigger.valueOf(json.getString("trigger")), RuleAction.valueOf(json.getString("action")),
            readStates(json.getJSONObject("states")).keys, json.getLong("cooldown"))
    }
    fun buildProfile(now: Long, self: String) = EnhancedEditors.profile(originalProfile(), name, states, now, self)
    fun buildRule(self: String) = EnhancedEditors.rule(originalRule().copy(enabled = enabled,
        trigger = trigger, action = action), name, states.keys, cooldown, self)

    fun encode(): String = JSONObject().put("profile", profile).put("new", isNew).put("original", original)
        .put("name", name).put("states", JSONObject(states)).put("cooldown", cooldown)
        .put("enabled", enabled).put("trigger", trigger.name).put("action", action.name)
        .put("search", search).toString()

    companion object {
        fun forProfile(value: FreezeProfile, isNew: Boolean): EnhancedEditorDraft {
            val original = JSONObject().put("id", value.id).put("name", value.name)
                .put("states", JSONObject(value.packageStates)).put("created", value.createdAtEpochMs)
                .put("updated", value.updatedAtEpochMs).toString()
            return EnhancedEditorDraft(true, isNew, original, value.name, value.packageStates.toMap())
        }
        fun forRule(value: FreezeRule, isNew: Boolean): EnhancedEditorDraft {
            val states = value.packageNames.associateWith { false }
            val original = JSONObject().put("id", value.id).put("name", value.name).put("states", JSONObject(states))
                .put("enabled", value.enabled).put("trigger", value.trigger.name).put("action", value.action.name)
                .put("cooldown", value.cooldownMillis).toString()
            return EnhancedEditorDraft(false, isNew, original, value.name, states,
                (value.cooldownMillis / 1000).toString(), value.enabled, value.trigger, value.action)
        }
        fun decode(value: String): EnhancedEditorDraft {
            val json = JSONObject(value)
            return EnhancedEditorDraft(json.getBoolean("profile"), json.getBoolean("new"), json.getString("original"),
                json.getString("name"), readStates(json.getJSONObject("states")), json.getString("cooldown"),
                json.getBoolean("enabled"), RuleTrigger.valueOf(json.getString("trigger")),
                RuleAction.valueOf(json.getString("action")), json.optString("search"))
        }
        private fun readStates(json: JSONObject): Map<String, Boolean> =
            json.keys().asSequence().associateWith { json.getBoolean(it) }
    }
}
