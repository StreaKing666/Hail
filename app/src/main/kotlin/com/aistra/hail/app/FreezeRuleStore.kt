package com.aistra.hail.app

import com.aistra.hail.utils.AtomicFileStorage
import android.content.Context
import androidx.preference.PreferenceManager
import com.aistra.hail.enhanced.domain.FreezeRule
import com.aistra.hail.enhanced.domain.RuleAction
import com.aistra.hail.enhanced.domain.RuleEngine
import com.aistra.hail.enhanced.domain.RuleExecutionState
import com.aistra.hail.enhanced.domain.RuleTrigger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class FreezeRuleStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val file = File(File(applicationContext.filesDir, "enhanced"), "rules.json")
    private val defaultPreferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)

    var isEnabled: Boolean
        get() = defaultPreferences.getBoolean(HailData.RULE_ENGINE_ENABLED, false)
        set(value) {
            check(defaultPreferences.edit().putBoolean(HailData.RULE_ENGINE_ENABLED, value).commit())
        }

    fun getRules(): List<FreezeRule> = AppDataAccess.access {
        val json = readDocument().let { if (it.has("rules")) it.getJSONArray("rules") else JSONArray() }
        val rules = List(json.length()) { index ->
            val item = json.getJSONObject(index)
            val packages = item.getJSONArray("packages")
            FreezeRule(
                id = item.getString("id"),
                name = item.getString("name"),
                enabled = item.optBoolean("enabled", true),
                trigger = RuleTrigger.valueOf(item.getString("trigger")),
                action = RuleAction.valueOf(item.getString("action")),
                packageNames = buildSet {
                    for (i in 0 until packages.length()) add(packages.getString(i))
                },
                cooldownMillis = item.optLong("cooldownMillis", 30_000L)
            )
        }
        RuleEngine.validate(rules)
    }

    fun saveRules(rules: List<FreezeRule>) = AppDataAccess.access {
        RuleEngine.validate(rules)
        val json = JSONArray()
        rules.forEach { rule ->
            json.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("name", rule.name)
                    .put("enabled", rule.enabled)
                    .put("trigger", rule.trigger.name)
                    .put("action", rule.action.name)
                    .put("cooldownMillis", rule.cooldownMillis)
                    .put("packages", JSONArray(rule.packageNames.sorted()))
            )
        }
        val document = readDocument().put("rules", json)
        writeDocument(document)
    }

    /** One transaction, including the caller's read/modify/write. */
    fun updateRules(change: (List<FreezeRule>) -> List<FreezeRule>) = AppDataAccess.access {
        saveRules(change(getRules()))
    }

    fun getExecutionState(): RuleExecutionState = AppDataAccess.access {
        val json = readDocument().optJSONObject("executionState") ?: JSONObject()
        val timesJson = json.optJSONObject("lastRunAtByRule") ?: JSONObject()
        val times = linkedMapOf<String, Long>()
        timesJson.keys().forEach { times[it] = timesJson.getLong(it) }
        val processedJson = json.optJSONArray("processedEventKeys") ?: JSONArray()
        val processed = buildSet {
            for (index in 0 until processedJson.length()) add(processedJson.getString(index))
        }
        RuleExecutionState(times, processed)
    }

    fun saveExecutionState(state: RuleExecutionState) = AppDataAccess.access {
        val times = JSONObject()
        state.lastRunAtByRule.forEach { (id, time) -> times.put(id, time) }
        val processed = JSONArray()
        state.processedEventKeys.forEach(processed::put)
        val document = readDocument().put(
            "executionState",
            JSONObject().put("lastRunAtByRule", times).put("processedEventKeys", processed)
        )
        writeDocument(document)
    }

    fun appendAudit(message: String) = AppDataAccess.access {
        val entries = readDocument().optJSONArray("audit") ?: JSONArray()
        val next = JSONArray()
        val old = List(entries.length()) { entries.getString(it) }
        (old + (System.currentTimeMillis().toString() + ": " + message))
            .takeLast(MAX_AUDIT_ENTRIES).forEach(next::put)
        writeDocument(readDocument().put("audit", next))
    }

    private fun readDocument(): JSONObject = AppDataAccess.access {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    }

    private fun writeDocument(document: JSONObject) =
        AtomicFileStorage.write(file, document.toString().toByteArray(Charsets.UTF_8))

    private companion object {
        val STORAGE_LOCK = Any()
        const val MAX_AUDIT_ENTRIES = 100
    }
}
