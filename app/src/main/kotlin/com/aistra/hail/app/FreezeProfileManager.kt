package com.aistra.hail.app

import android.content.Context
import androidx.preference.PreferenceManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.enhanced.domain.FreezeOperationReport
import com.aistra.hail.enhanced.domain.FreezeOperationResult
import com.aistra.hail.enhanced.domain.FreezeProfile
import com.aistra.hail.enhanced.domain.FreezeProfileEngine
import java.util.UUID

internal data class FreezeProfileApplyResult(
    val profile: FreezeProfile,
    val results: List<FreezeOperationResult>,
    val conflicts: Int
) {
    val complete: Boolean get() = !FreezeOperationReport.summarize(results).incomplete
}

internal object FreezeProfileManager {
    private const val ACTIVE_PROFILE = HailData.ACTIVE_PROFILE_ID

    fun createFromTracked(context: Context, name: String): FreezeProfile {
        val packages = HailData.checkedList
            .filter { it.packageName != app.packageName }
            .map { it.packageName }
            .toSet()
        require(packages.isNotEmpty()) { "Add applications to Hail before creating a profile" }
        val now = System.currentTimeMillis()
        return FreezeProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            packageStates = packages.associateWith { AppManager.isAppFrozen(it) },
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        ).also { FreezeProfileStore(context).save(it) }
    }

    fun apply(context: Context, profileId: String, allowConflicts: Boolean = false): FreezeProfileApplyResult {
        val store = FreezeProfileStore(context)
        val profiles = store.getAll()
        val target = profiles.firstOrNull { it.id == profileId }
            ?: throw IllegalArgumentException("Freeze profile was not found")
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val active = profiles.firstOrNull { it.id == preferences.getString(ACTIVE_PROFILE, null) }
        val packages = (target.packageStates.keys + (active?.packageStates?.keys ?: emptySet())).toSet()
        val currentStates = packages.associateWith { packageName ->
            runCatching { AppManager.isAppFrozen(packageName) }.getOrNull()
        }
        val plan = FreezeProfileEngine.planSwitch(
            active = active,
            target = target,
            allProfiles = profiles,
            currentStates = currentStates,
            protectedPackages = setOf(context.packageName)
        )
        if (plan.conflicts.isNotEmpty() && !allowConflicts) {
            return FreezeProfileApplyResult(target, emptyList(), plan.conflicts.size)
        }

        val results = plan.operations.entries
            .groupBy { it.value }
            .flatMap { (frozen, entries) ->
                FreezeControllerFacade.execute(entries.map { it.key }, frozen)
            }
        if (!FreezeOperationReport.summarize(results).incomplete) {
            check(preferences.edit().putString(ACTIVE_PROFILE, target.id).commit()) {
                "Could not save active freeze profile"
            }
        }
        return FreezeProfileApplyResult(target, results, plan.conflicts.size)
    }

    fun delete(context: Context, profileId: String) {
        FreezeProfileStore(context).delete(profileId)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getString(ACTIVE_PROFILE, null) == profileId) {
            preferences.edit().remove(ACTIVE_PROFILE).commit()
        }
    }
}
