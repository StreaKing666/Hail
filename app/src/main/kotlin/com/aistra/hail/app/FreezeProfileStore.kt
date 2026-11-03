package com.aistra.hail.app

import com.aistra.hail.utils.AtomicFileStorage
import android.content.Context
import com.aistra.hail.enhanced.domain.FreezeProfile
import com.aistra.hail.enhanced.domain.FreezeProfileEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class FreezeProfileStore(context: Context) {
    private val file = File(File(context.applicationContext.filesDir, "enhanced"), "profiles.json")

    fun getAll(): List<FreezeProfile> = synchronized(STORAGE_LOCK) {
        val json = JSONArray(if (file.isFile) file.readText() else "[]")
        val profiles = List(json.length()) { index ->
            val item = json.getJSONObject(index)
            val statesJson = item.getJSONObject("packageStates")
            val states = linkedMapOf<String, Boolean>()
            statesJson.keys().forEach { packageName -> states[packageName] = statesJson.getBoolean(packageName) }
            FreezeProfile(
                id = item.getString("id"),
                name = item.getString("name"),
                packageStates = states,
                createdAtEpochMs = item.getLong("createdAtEpochMs"),
                updatedAtEpochMs = item.optLong("updatedAtEpochMs", item.getLong("createdAtEpochMs"))
            )
        }
        FreezeProfileEngine.validate(profiles)
    }

    fun save(profile: FreezeProfile) = synchronized(STORAGE_LOCK) {
        val profiles = getAll().filterNot { it.id == profile.id } + profile
        FreezeProfileEngine.validate(profiles)
        writeProfiles(profiles)
    }

    fun delete(id: String) = synchronized(STORAGE_LOCK) {
        writeProfiles(getAll().filterNot { it.id == id })
    }

    private fun writeProfiles(profiles: List<FreezeProfile>) {
        val json = JSONArray()
        profiles.forEach { item ->
            val states = JSONObject()
            item.packageStates.forEach { (packageName, frozen) -> states.put(packageName, frozen) }
            json.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("createdAtEpochMs", item.createdAtEpochMs)
                    .put("updatedAtEpochMs", item.updatedAtEpochMs)
                    .put("packageStates", states)
            )
        }
        writeAtomically(file, json.toString())
    }

    private fun writeAtomically(target: File, content: String) =
        AtomicFileStorage.write(target, content.toByteArray(Charsets.UTF_8))

    private companion object { val STORAGE_LOCK = Any() }
}
