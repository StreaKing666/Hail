package com.aistra.hail.ui.api

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.aistra.hail.ui.main.ManualOperationsViewModel
import com.aistra.hail.ui.main.ManualResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.app.RecoveryStatus
import com.aistra.hail.ui.settings.EnhancedOperationsViewModel
import com.aistra.hail.ui.settings.EnhancedResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.ApiDispatchPolicy
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.app.FreezeProfileManager
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork.setAutoFreeze

class ApiActivity : ComponentActivity() {
    private val profileOperations: EnhancedOperationsViewModel by viewModels()
    private val manualOperations: ManualOperationsViewModel by viewModels()
    private var savedRequestIssued = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedRequestIssued = savedInstanceState?.getBoolean(REQUEST_ISSUED) == true
        lifecycleScope.launch {
            val recovery = AppDataAccess.status.first { it != RecoveryStatus.CHECKING }
            if (recovery != RecoveryStatus.READY) {
                setErrorDialog(IllegalStateException(getString(R.string.recovery_failed_message)))
                return@launch
            }
            when (ApiDispatchPolicy.decide(
                savedRequestIssued, manualOperations.state.value.id, profileOperations.state.value.id
            )) {
                ApiDispatchPolicy.Decision.DISPATCH ->
                    runCatching { handleAction(intent.action) }.onFailure(::setErrorDialog)
                ApiDispatchPolicy.Decision.RESUME_RETAINED -> Unit
                ApiDispatchPolicy.Decision.INTERRUPTED ->
                    setErrorDialog(IllegalStateException(getString(R.string.state_unverified)))
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                manualOperations.state.collect { event ->
                    event.error?.let { setErrorDialog(IllegalStateException(it)) }
                    event.result?.let { result ->
                        manualOperations.consume(event.id)
                        runCatching {
                            result.intent?.let(::startActivity)
                            if (result.messageRes != 0) HUI.showToast(result.messageRes, result.value ?: "")
                            finish()
                        }.onFailure(::setErrorDialog)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                profileOperations.state.collect { event ->
                    event.error?.let { setErrorDialog(IllegalStateException(it)) }
                    (event.result as? EnhancedResult.Applied)?.let { applied ->
                        if (applied.value.conflicts > 0) setErrorDialog(IllegalStateException(getString(R.string.profile_conflicts, applied.value.conflicts)))
                        else if (!applied.value.complete) setErrorDialog(IllegalStateException(applied.value.results.filterNot {
                            it.stateVerified }.joinToString("\n") { it.packageName + ": " + it.reason }))
                        else {
                            profileOperations.consume(event.id)
                            HUI.showToast(R.string.profile_applied, applied.value.profile.name)
                            finish()
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Include redirect clicks as well as the action initially dispatched by onCreate.
        savedRequestIssued = ApiDispatchPolicy.requestIssued(
            savedRequestIssued, manualOperations.state.value.id, profileOperations.state.value.id
        )
        outState.putBoolean(REQUEST_ISSUED, savedRequestIssued)
        super.onSaveInstanceState(outState)
    }

    private fun handleAction(action: String?) {
        when (action) {
            Intent.ACTION_SHOW_APP_INFO -> {
                val pkg = requirePackage
                setContent { AppTheme { RedirectBottomSheet(pkg) } }
            }
            Intent.ACTION_VIEW -> handleSchema(intent.data)
            HailApi.ACTION_APPLY_PROFILE -> applyProfile(requireProfileId)
            HailApi.ACTION_LAUNCH -> {
                val pkg = requirePackage
                val tag = optionalTag
                manualOperations.run { launchApp(pkg, tag) }
            }
            HailApi.ACTION_FREEZE, HailApi.ACTION_UNFREEZE -> {
                val pkg = requirePackage
                val frozen = action == HailApi.ACTION_FREEZE
                manualOperations.run { setAppFrozen(pkg, frozen) }
            }
            HailApi.ACTION_FREEZE_TAG, HailApi.ACTION_UNFREEZE_TAG -> {
                val tag = optionalTag ?: throw IllegalArgumentException("Tag must not be null")
                val frozen = action == HailApi.ACTION_FREEZE_TAG
                manualOperations.run { setListFrozen(frozen, tag, skipWhitelisted = frozen) }
            }
            HailApi.ACTION_FREEZE_ALL -> manualOperations.run { setListFrozen(true) }
            HailApi.ACTION_UNFREEZE_ALL -> manualOperations.run { setListFrozen(false) }
            HailApi.ACTION_FREEZE_NON_WHITELISTED -> manualOperations.run {
                setListFrozen(true, skipWhitelisted = true)
            }
            HailApi.ACTION_FREEZE_AUTO -> manualOperations.run { setAutoFreeze(false); ManualResult() }
            HailApi.ACTION_LOCK -> manualOperations.run { lockScreen(false) }
            HailApi.ACTION_LOCK_FREEZE -> manualOperations.run { lockScreen(true) }
            else -> throw IllegalArgumentException("Unknown action:\n$action")
        }
    }

    /**
     * Handle schema actions
     *
     * hail://launch?package=xxx
     * hail://freeze?package=xxx
     * hail://unfreeze?package=xxx
     * hail://freeze_tag?tag=xxx
     * hail://unfreeze_tag?tag=xxx
     * hail://freeze_all
     * hail://unfreeze_all
     * hail://freeze_non_whitelisted
     * hail://freeze_auto
     * hail://lock
     * hail://lock_freeze
     */
    private fun handleSchema(uri: Uri?) {
        if (uri?.scheme != "hail") throw IllegalArgumentException("Unknown scheme:\n${uri?.scheme}")
        handleAction(
            when (uri.host) {
                "launch" -> HailApi.ACTION_LAUNCH
                "freeze" -> HailApi.ACTION_FREEZE
                "unfreeze" -> HailApi.ACTION_UNFREEZE
                "freeze_tag" -> HailApi.ACTION_FREEZE_TAG
                "unfreeze_tag" -> HailApi.ACTION_UNFREEZE_TAG
                "freeze_all" -> HailApi.ACTION_FREEZE_ALL
                "unfreeze_all" -> HailApi.ACTION_UNFREEZE_ALL
                "freeze_non_whitelisted" -> HailApi.ACTION_FREEZE_NON_WHITELISTED
                "freeze_auto" -> HailApi.ACTION_FREEZE_AUTO
                "lock" -> HailApi.ACTION_LOCK
                "lock_freeze" -> HailApi.ACTION_LOCK_FREEZE
                "profile" -> HailApi.ACTION_APPLY_PROFILE
                else -> throw IllegalArgumentException("Unknown host:\n${uri.host}")
            }
        )
    }

    private fun applyProfile(profileId: String) {
        if (profileOperations.state.value.id != 0L || manualOperations.state.value.id != 0L) return
        profileOperations.run { EnhancedResult.Applied(FreezeProfileManager.apply(app, profileId, allowConflicts = false)) }
    }

    private val requireProfileId: String
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailApi.EXTRA_PROFILE_ID)
            else getStringExtra(HailApi.EXTRA_PROFILE_ID)
        }?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Profile ID must not be null")

    private fun setErrorDialog(t: Throwable) = setContent { AppTheme { ErrorDialog(t) } }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RedirectBottomSheet(pkg: String) {
        val label by produceState(pkg, pkg) {
            value = withContext(Dispatchers.IO) {
                HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(app.packageManager)?.toString() ?: pkg
            }
        }
        ModalBottomSheet(
            onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column {
                Text(
                    text = label,
                    modifier = Modifier.padding(
                        horizontal = dimensionResource(R.dimen.padding_medium),
                        vertical = dimensionResource(R.dimen.padding_small)
                    ),
                    style = MaterialTheme.typography.headlineSmall
                )
                ClickableItem(Icons.AutoMirrored.Outlined.Launch, R.string.action_launch) {
                    manualOperations.run { launchApp(pkg) }
                }
                ClickableItem(Icons.Rounded.AcUnit, R.string.action_freeze) {
                    manualOperations.run { setAppFrozen(pkg, true, addChecked = true) }
                }
                ClickableItem(Icons.Rounded.BrightnessLow, R.string.action_unfreeze) {
                    manualOperations.run { setAppFrozen(pkg, false) }
                }
            }
        }
    }

    @Composable
    private fun ClickableItem(icon: ImageVector, @StringRes title: Int, onClick: () -> Unit) = Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = {
            runCatching { onClick() }.onFailure(::setErrorDialog)
        }), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))
        )
        Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
    }

    @Composable
    private fun ErrorDialog(t: Throwable) = AlertDialog(
        text = { Text(text = t.message ?: t.stackTraceToString()) },
        onDismissRequest = ::finish,
        confirmButton = {
            TextButton(onClick = ::finish) {
                Text(text = stringResource(android.R.string.ok))
            }
        })

    private val requirePackage: String
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_PACKAGE)
            else getStringExtra(
                if (action != Intent.ACTION_SHOW_APP_INFO) HailData.KEY_PACKAGE
                else if (HTarget.N) Intent.EXTRA_PACKAGE_NAME
                else "android.intent.extra.PACKAGE_NAME"
            )
        }?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Package must not be null")

    private val optionalTag: String?
        get() = intent.run {
            if (action == Intent.ACTION_VIEW) data?.getQueryParameter(HailData.KEY_TAG)
            else getStringExtra(HailData.KEY_TAG)
        }

    // Workers are static: retained operations must never capture an Activity or its views.
    private companion object {
        const val REQUEST_ISSUED = "apiRequestIssued"

        fun requireInstalled(pkg: String) {
            HPackages.getApplicationInfoOrNull(pkg)
                ?: throw NameNotFoundException(app.getString(R.string.app_not_installed))
        }

        fun tagId(tag: String): Int = HailData.tags.find { it.first == tag }?.second
            ?: throw IllegalStateException("Tag unavailable:\n$tag")

        fun launchApp(pkg: String, tag: String? = null): ManualResult {
            requireInstalled(pkg)
            // A bad optional launch tag has historically left the package launch available.
            val id = tag?.let { runCatching { tagId(it) }.getOrNull() }
            val batchResult = if (id != null) setListFrozen(false, resolvedTagId = id) else ManualResult()
            val before = HPackages.getFreezeState(pkg)
                ?: throw NameNotFoundException(app.getString(R.string.app_not_installed))
            if (before.disabled || before.hidden == true || before.suspended) {
                if (!AppManager.setAppFrozen(pkg, false)) {
                    throw IllegalStateException(app.getString(R.string.operation_failed, pkg))
                }
                val after = HPackages.getFreezeState(pkg)
                    ?: throw NameNotFoundException(app.getString(R.string.app_not_installed))
                if (after.disabled || after.hidden == true || after.suspended) {
                    throw IllegalStateException(app.getString(R.string.operation_failed, pkg))
                }
                app.setAutoFreezeService()
            }
            if (HailData.workingMode == HailData.MODE_ISLAND_HIDE) HIsland.ensureLaunchIntentExists(pkg)
            val launchIntent = app.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw ActivityNotFoundException(app.getString(R.string.activity_not_found))
            HShortcuts.addDynamicShortcut(pkg)
            return batchResult.copy(intent = launchIntent)
        }

        fun setAppFrozen(pkg: String, frozen: Boolean, addChecked: Boolean = false): ManualResult {
            requireInstalled(pkg)
            if (addChecked && !HailData.isChecked(pkg)) HailData.addCheckedApp(pkg)
            if (frozen && !HailData.isChecked(pkg)) throw SecurityException("Package not checked")
            val before = AppManager.observeAppFrozen(pkg)
            val count = if (before == frozen) "0" else {
                if (!AppManager.setAppFrozen(pkg, frozen)) {
                    throw IllegalStateException(app.getString(R.string.operation_failed, pkg))
                }
                "1"
            }
            app.setAutoFreezeService()
            return ManualResult(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, count)
        }

        fun setListFrozen(
            frozen: Boolean, tag: String? = null, skipWhitelisted: Boolean = false, resolvedTagId: Int? = null
        ): ManualResult {
            val id = resolvedTagId ?: tag?.let { tagId(it) }
            val filtered = HailData.checkedList.filter {
                (id == null || id in it.tagIdList) && AppManager.observeAppFrozen(it.packageName) != frozen &&
                    !(skipWhitelisted && it.whitelisted)
            }
            val result = AppManager.setListFrozen(frozen, *filtered.toTypedArray())
            app.setAutoFreezeService()
            return ManualResult(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result)
        }

        fun lockScreen(freezeAll: Boolean): ManualResult {
            val result = if (freezeAll) setListFrozen(true) else ManualResult()
            if (!AppManager.lockScreen) throw IllegalStateException(app.getString(R.string.operation_failed, "Lock screen"))
            return result
        }
    }
}
