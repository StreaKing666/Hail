package com.aistra.hail.app

import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.enhanced.domain.FreezeAction
import com.aistra.hail.enhanced.domain.FreezeController
import com.aistra.hail.enhanced.domain.FreezeJournalEntry
import com.aistra.hail.enhanced.domain.FreezeOperationResult

internal object FreezeControllerFacade {
    fun execute(packageNames: Collection<String>, frozen: Boolean): List<FreezeOperationResult> = AppDataAccess.access {
        val mode = HailData.workingMode
        val controller = FreezeController(
            backends = listOf(ModeFreezeBackend(mode)),
            journal = SharedPreferencesFreezeJournal(app)
        )
        controller.execute(
            packageNames = packageNames,
            action = if (frozen) FreezeAction.FREEZE else FreezeAction.UNFREEZE,
            backendId = mode,
            userId = android.os.Process.myUid() / 100_000,
            protectedPackages = setOf(BuildConfig.APPLICATION_ID)
        )
    }

    fun recoverPending() = AppDataAccess.access {
        // Journal entries retain the backend that initiated them. Observe with that backend
        // after restart, even if the user has since selected another working mode.
        val backends = HailData.WORKING_MODE_VALUES
            .filterNot { it == HailData.MODE_DEFAULT }
            .map(::ModeFreezeBackend)
        FreezeController(
            backends = backends,
            journal = SharedPreferencesFreezeJournal(app)
        ).recoverPending()
    }

    fun recentOperations(limit: Int = 50): List<FreezeJournalEntry> =
        AppDataAccess.access { SharedPreferencesFreezeJournal(app).recent(limit) }
}
