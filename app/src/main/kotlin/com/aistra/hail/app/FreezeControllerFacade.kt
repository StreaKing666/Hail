package com.aistra.hail.app

import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.enhanced.domain.FreezeAction
import com.aistra.hail.enhanced.domain.FreezeController
import com.aistra.hail.enhanced.domain.FreezeJournalEntry
import com.aistra.hail.enhanced.domain.FreezeOperationResult

internal object FreezeControllerFacade {
    @Synchronized
    fun execute(packageNames: Collection<String>, frozen: Boolean): List<FreezeOperationResult> {
        val mode = HailData.workingMode
        val controller = FreezeController(
            backends = listOf(ModeFreezeBackend(mode)),
            journal = SharedPreferencesFreezeJournal(app)
        )
        return controller.execute(
            packageNames = packageNames,
            action = if (frozen) FreezeAction.FREEZE else FreezeAction.UNFREEZE,
            backendId = mode,
            userId = android.os.Process.myUid() / 100_000,
            protectedPackages = setOf(BuildConfig.APPLICATION_ID)
        )
    }

    @Synchronized
    fun recoverPending() {
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

    @Synchronized
    fun recentOperations(limit: Int = 50): List<FreezeJournalEntry> =
        SharedPreferencesFreezeJournal(app).recent(limit)
}
