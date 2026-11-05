package com.aistra.hail.app

import android.content.Context
import com.aistra.hail.enhanced.domain.FreezeAction
import com.aistra.hail.enhanced.domain.FreezeJournalEntry
import com.aistra.hail.enhanced.domain.FreezeJournalState
import com.aistra.hail.enhanced.domain.FreezeOperationJournal
import org.json.JSONArray
import org.json.JSONObject

internal class SharedPreferencesFreezeJournal(context: Context) : FreezeOperationJournal {
    private val preferences = context.applicationContext.getSharedPreferences(
        "hail_enhanced_freeze_journal", Context.MODE_PRIVATE
    )

    @Synchronized
    override fun appendPending(entry: FreezeJournalEntry) {
        val entries = readEntries().toMutableList()
        entries.removeAll { it.id == entry.id }
        entries += entry
        writeEntries(entries.takeLast(MAX_ENTRIES))
    }

    @Synchronized
    override fun complete(entry: FreezeJournalEntry) {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) entries[index] = entry else entries += entry
        writeEntries(entries.takeLast(MAX_ENTRIES))
    }

    @Synchronized
    override fun pending(): List<FreezeJournalEntry> =
        readEntries().filter { it.state == FreezeJournalState.PENDING }

    @Synchronized
    override fun recent(limit: Int): List<FreezeJournalEntry> {
        require(limit >= 0)
        return readEntries().takeLast(limit).asReversed()
    }

    private fun readEntries(): List<FreezeJournalEntry> {
        val json = JSONArray(preferences.getString(KEY_ENTRIES, "[]"))
        return List(json.length()) { index ->
            val item = json.getJSONObject(index)
            FreezeJournalEntry(
                id = item.getString("id"),
                packageName = item.getString("packageName"),
                userId = item.getInt("userId"),
                backendId = item.getString("backendId"),
                action = FreezeAction.valueOf(item.getString("action")),
                targetFrozen = item.getBoolean("targetFrozen"),
                createdAtEpochMs = item.getLong("createdAtEpochMs"),
                state = FreezeJournalState.valueOf(item.getString("state")),
                reason = item.optString("reason").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    private fun writeEntries(entries: List<FreezeJournalEntry>) {
        val json = JSONArray()
        entries.forEach { entry ->
            json.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("packageName", entry.packageName)
                    .put("userId", entry.userId)
                    .put("backendId", entry.backendId)
                    .put("action", entry.action.name)
                    .put("targetFrozen", entry.targetFrozen)
                    .put("createdAtEpochMs", entry.createdAtEpochMs)
                    .put("state", entry.state.name)
                    .put("reason", entry.reason ?: JSONObject.NULL)
            )
        }
        check(preferences.edit().putString(KEY_ENTRIES, json.toString()).commit()) {
            "Could not persist freeze operation journal"
        }
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 200
    }
}
