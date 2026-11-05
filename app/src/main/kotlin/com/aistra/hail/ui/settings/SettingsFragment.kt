package com.aistra.hail.ui.settings

import android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.viewModels
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.enhanced.domain.EnhancedEditorDraft
import com.aistra.hail.enhanced.domain.FreezeJournalEntry
import com.aistra.hail.enhanced.domain.FreezeJournalState
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.app.FreezeProfileManager
import com.aistra.hail.app.FreezeProfileStore
import com.aistra.hail.app.FreezeRuleStore
import com.aistra.hail.app.FreezeControllerFacade
import com.aistra.hail.enhanced.domain.EnhancedEditors
import com.aistra.hail.enhanced.domain.FreezeProfile
import com.aistra.hail.enhanced.domain.FreezeRule
import com.aistra.hail.enhanced.domain.RuleAction
import com.aistra.hail.enhanced.domain.RuleTrigger
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.*
import rikka.shizuku.Shizuku
import java.util.UUID

class SettingsFragment : MainFragment(), MenuProvider {
    private val operations: EnhancedOperationsViewModel by viewModels()
    private var modeRequest = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.setFragmentResultListener(EnhancedEditorDialogFragment.RESULT, viewLifecycleOwner) { _, _ ->
            refreshRuleService()
            HUI.showToast(R.string.action_save)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                operations.state.collect { event ->
                    OperationProgressDialog.update(childFragmentManager, event.busy)
                    if (event.error != null) {
                        HUI.showToast(R.string.operation_failed, event.error, true)
                        operations.consume(event.id)
                    }
                    event.result?.let { result ->
                        operations.consume(event.id)
                        when (result) {
                            is EnhancedResult.Profiles -> displayProfiles(result.values)
                            is EnhancedResult.Rules -> displayRules(result.values)
                            is EnhancedResult.Journal -> displayJournal(result.values)
                            is EnhancedResult.ProfileDraft -> openEditor(EnhancedEditorDraft.forProfile(result.value, true))
                            is EnhancedResult.Applied -> displayApplied(result.value, result.confirmed)
                            is EnhancedResult.Changed -> { refreshRuleService(); HUI.showToast(R.string.action_save) }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun refreshRuleService() {
        runCatching { app.setAutoFreezeService() }.onFailure { HUI.showToast(R.string.rule_service_failed) }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    ProvidePreferenceLocals {
                        SettingsScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val autoFreezeAfterLock = rememberPreferenceState(HailData.AUTO_FREEZE_AFTER_LOCK, false)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            listPreference(
                key = HailData.WORKING_MODE,
                defaultValue = HailData.MODE_DEFAULT,
                onValueChange = ::onWorkingModeChange,
                values = HailData.WORKING_MODE_VALUES,
                entriesId = R.array.working_mode_entries,
                titleId = R.string.working_mode,
                icon = Icons.Outlined.Adb,
                type = ListPreferenceType.ALERT_DIALOG
            )
            switchPreference(
                key = HailData.BIOMETRIC_LOGIN,
                defaultValue = false,
                onValueChange = { _, value ->
                    if (value) resetDynamicShortcuts()
                    true
                },
                titleId = R.string.action_biometric,
                icon = Icons.Outlined.Fingerprint
            )
            horizontalDivider()
            preferenceCategory(key = "customize", title = { Text(text = stringResource(R.string.title_customize)) })
            listPreference(
                key = HailData.APP_THEME,
                defaultValue = HailData.FOLLOW_SYSTEM,
                onValueChange = { _, value ->
                    app.setAppTheme(value)
                    true
                },
                values = HailData.APP_THEME_VALUES,
                entriesId = R.array.app_theme_entries,
                titleId = R.string.app_theme,
                icon = Icons.Outlined.DarkMode
            )
            listPreference(
                key = HailData.ICON_PACK,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, _ ->
                    AppIconCache.clear()
                    true
                },
                values = mutableListOf(HailData.ACTION_NONE).apply {
                    addAll(Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME").let {
                        if (HTarget.T) app.packageManager.queryIntentActivities(
                            it, PackageManager.ResolveInfoFlags.of(0)
                        ) else app.packageManager.queryIntentActivities(it, 0)
                    }.map { it.activityInfo.packageName })
                },
                titleId = R.string.icon_pack,
                icon = Icons.Outlined.Palette,
                summary = { iconPackName(it) },
                valueToText = ::iconPackName
            )
            switchPreference(
                key = HailData.GRAYSCALE_ICON,
                defaultValue = true,
                titleId = R.string.grayscale_icon,
                icon = Icons.Outlined.FilterBAndW
            )
            switchPreference(
                key = HailData.COMPACT_ICON,
                defaultValue = false,
                titleId = R.string.compact_icon,
                icon = Icons.Outlined.Apps
            )
            switchPreference(
                key = HailData.SYNTHESIZE_ADAPTIVE_ICONS,
                defaultValue = false,
                titleId = R.string.synthesize_adaptive_icons,
                icon = Icons.Outlined.Layers
            )
            sliderPreference(
                key = HailData.HOME_FONT_SIZE,
                defaultValue = 14f,
                title = { Text(text = stringResource(R.string.home_font_size)) },
                valueRange = 11f..16f,
                valueSteps = 4,
                icon = { Icon(imageVector = Icons.Outlined.TextFields, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.FUZZY_SEARCH,
                defaultValue = false,
                titleId = R.string.fuzzy_search,
                icon = Icons.AutoMirrored.Outlined.ManageSearch
            )
            switchPreference(
                key = HailData.NINE_KEY_SEARCH,
                defaultValue = false,
                titleId = R.string.nine_key,
                icon = Icons.Outlined.Dialpad
            )
            listPreference(
                key = HailData.TILE_ACTION,
                defaultValue = HailData.tileAction,
                values = HailData.TILE_ACTION_VALUES,
                entriesId = R.array.tile_action_entries,
                titleId = R.string.tile_action,
                icon = Icons.Outlined.DashboardCustomize
            )
            horizontalDivider()
            preferenceCategory(key = "auto_freeze", title = { Text(text = stringResource(R.string.auto_freeze)) })
            switchPreference(
                rememberState = { autoFreezeAfterLock },
                onValueChange = { _, value ->
                    app.setAutoFreezeService(value)
                    true
                },
                titleId = R.string.auto_freeze_after_lock,
                icon = Icons.Outlined.ScreenLockPortrait
            )
            sliderPreference(
                key = HailData.AUTO_FREEZE_DELAY,
                defaultValue = 0f,
                title = { Text(text = stringResource(R.string.auto_freeze_delay)) },
                valueRange = 0f..30f,
                valueSteps = 29,
                enabled = { autoFreezeAfterLock.value },
                icon = { Icon(imageVector = Icons.Outlined.LockClock, contentDescription = null) },
                valueText = { Text(text = "%.0f".format(it)) },
            )
            switchPreference(
                key = HailData.SKIP_WHILE_CHARGING,
                defaultValue = false,
                titleId = R.string.skip_while_charging,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.BatteryChargingFull
            )
            switchPreference(
                key = HailData.SKIP_FOREGROUND_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    if (value && !HSystem.checkOpUsageStats(requireContext())) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_foreground_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.Android
            )
            switchPreference(
                key = HailData.SKIP_NOTIFYING_APP,
                defaultValue = false,
                onValueChange = { _, value ->
                    val isGranted = NotificationManagerCompat.getEnabledListenerPackages(requireContext())
                        .contains(requireContext().packageName)
                    if (value && !isGranted) {
                        app.setAutoFreezeServiceEnabled(true)
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        false
                    } else true
                },
                titleId = R.string.skip_notifying_app,
                enabled = autoFreezeAfterLock.value,
                icon = Icons.Outlined.NotificationsActive
            )
            horizontalDivider()
            preferenceCategory(key = "shortcuts", title = { Text(text = stringResource(R.string.title_shortcuts)) })
            preference(
                key = "add_pin_shortcut",
                title = { Text(text = stringResource(R.string.action_add_pin_shortcut)) },
                icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.Shortcut, contentDescription = null) },
                onClick = ::addPinShortcut
            )
            listPreference(
                key = HailData.DYNAMIC_SHORTCUT_ACTION,
                defaultValue = HailData.ACTION_NONE,
                onValueChange = { _, action ->
                    HShortcuts.removeAllDynamicShortcuts()
                    HShortcuts.addDynamicShortcutAction(action)
                    true
                },
                values = HailData.DYNAMIC_SHORTCUT_ACTIONS,
                entriesId = R.array.dynamic_shortcut_entries,
                titleId = R.string.dynamic_shortcut_action,
                icon = Icons.Outlined.AppShortcut
            )
            preference(
                key = "clear_dynamic_shortcuts",
                title = { Text(text = stringResource(R.string.action_clear_dynamic_shortcuts)) },
                icon = { Icon(imageVector = Icons.Outlined.CleaningServices, contentDescription = null) },
                onClick = ::resetDynamicShortcuts
            )

            horizontalDivider()
            preferenceCategory(
                key = "enhanced_navigation",
                title = { Text(text = stringResource(R.string.enhanced_navigation)) }
            )
            listPreference(
                key = HailData.NAVIGATION_LAYOUT,
                defaultValue = HailData.NAV_AUTO,
                onValueChange = { _, value ->
                    activity.applyNavigationLayout(value)
                    true
                },
                values = HailData.NAVIGATION_LAYOUT_VALUES,
                entriesId = R.array.navigation_layout_entries,
                titleId = R.string.navigation_layout,
                icon = Icons.Outlined.Navigation
            )
            listPreference(
                key = HailData.APP_LIST_LAYOUT,
                defaultValue = HailData.APP_LIST_STANDARD,
                values = HailData.APP_LIST_LAYOUT_VALUES,
                entriesId = R.array.app_list_layout_entries,
                titleId = R.string.app_list_layout,
                icon = Icons.Outlined.ViewList
            )
            horizontalDivider()
            preferenceCategory(
                key = "enhanced_profiles",
                title = { Text(text = stringResource(R.string.enhanced_profiles)) }
            )
            preference(
                key = "manage_profiles",
                title = { Text(text = stringResource(R.string.profile_manage)) },
                icon = { Icon(imageVector = Icons.Outlined.Folder, contentDescription = null) },
                onClick = ::showProfilesDialog
            )
            preference(
                key = "freeze_operation_log",
                title = { Text(text = stringResource(R.string.freeze_operation_log)) },
                icon = { Icon(imageVector = Icons.Outlined.History, contentDescription = null) },
                onClick = ::showFreezeOperationLog
            )
            horizontalDivider()
            preferenceCategory(
                key = "enhanced_automation",
                title = { Text(text = stringResource(R.string.enhanced_automation)) }
            )
            switchPreference(
                key = HailData.RULE_ENGINE_ENABLED,
                defaultValue = false,
                onValueChange = { _, value ->
                    operations.run {
                        AppDataAccess.access { FreezeRuleStore(app).isEnabled = value }
                        EnhancedResult.Changed
                    }
                    false
                },
                titleId = R.string.rule_engine_enabled,
                icon = Icons.Outlined.AutoMode
            )
            preference(
                key = "manage_rules",
                title = { Text(text = stringResource(R.string.rule_manage)) },
                icon = { Icon(imageVector = Icons.Outlined.Rule, contentDescription = null) },
                onClick = ::showRulesDialog
            )
        }
    }

    private fun LazyListScope.horizontalDivider() = item { HorizontalDivider() }

    private fun LazyListScope.switchPreference(
        rememberState: @Composable () -> MutableState<Boolean>,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = item(key = titleId, contentType = "SwitchPreference") {
        val state = rememberState()
        SwitchPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            title = { Text(text = stringResource(titleId)) },
            enabled = enabled,
            icon = { Icon(imageVector = icon, contentDescription = null) })
    }

    private fun LazyListScope.switchPreference(
        key: String,
        defaultValue: Boolean,
        onValueChange: (MutableState<Boolean>, Boolean) -> Boolean = { _, _ -> true },
        @StringRes titleId: Int,
        enabled: Boolean = true,
        icon: ImageVector,
    ) = switchPreference(
        rememberState = { rememberPreferenceState(key, defaultValue) },
        onValueChange = onValueChange,
        titleId = titleId,
        enabled = enabled,
        icon = icon
    )

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @StringRes titleId: Int,
        icon: ImageVector,
        summary: @Composable (String) -> String,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU,
        valueToText: (String) -> String
    ) = item(key = key, contentType = "ListPreference") {
        val state = rememberPreferenceState(key, defaultValue)
        ListPreference(
            value = state.value,
            onValueChange = { if (onValueChange(state, it)) state.value = it },
            values = values,
            title = { Text(text = stringResource(titleId)) },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            summary = { Text(text = summary(state.value)) },
            type = type,
            valueToText = { AnnotatedString(valueToText(it)) })
    }

    private fun LazyListScope.listPreference(
        key: String,
        defaultValue: String,
        onValueChange: (MutableState<String>, String) -> Boolean = { _, _ -> true },
        values: List<String>,
        @ArrayRes entriesId: Int,
        @StringRes titleId: Int,
        icon: ImageVector,
        type: ListPreferenceType = ListPreferenceType.DROPDOWN_MENU
    ) = listPreference(
        key = key,
        defaultValue = defaultValue,
        onValueChange = onValueChange,
        values = values,
        titleId = titleId,
        icon = icon,
        summary = { it.toEntry(values, entriesId) },
        type = type,
        valueToText = { it.toEntry(values, entriesId) })

    private fun String.toEntry(values: List<String>, @ArrayRes entriesId: Int): String =
        resources.getStringArray(entriesId)[values.indexOf(this)]

    private fun resetDynamicShortcuts() {
        HShortcuts.removeAllDynamicShortcuts()
        HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
    }

    private fun iconPackName(pack: String): String = if (pack == HailData.ACTION_NONE) getString(R.string.action_none)
    else HPackages.getApplicationInfoOrNull(pack)?.loadLabel(app.packageManager)?.toString() ?: pack

    private fun addPinShortcut() {
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_add_pin_shortcut)
            .setItems(R.array.pin_shortcut_entries) { _, which ->
                when (which) {
                    0 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_freeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_frozen_shortcut
                                )!!,
                                HailApi.ACTION_FREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_FREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    1 -> MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                            val tag = HailData.tags[index].first
                            HShortcuts.addPinShortcut(
                                AppCompatResources.getDrawable(
                                    requireContext(), R.drawable.ic_round_unfrozen_shortcut
                                )!!,
                                HailApi.ACTION_UNFREEZE_TAG + tag,
                                tag,
                                HailApi.getIntentForTag(HailApi.ACTION_UNFREEZE_TAG, tag)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()

                    2 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_ALL,
                        getString(R.string.action_freeze_all),
                        Intent(HailApi.ACTION_FREEZE_ALL)
                    )

                    3 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_unfrozen_shortcut
                        )!!,
                        HailApi.ACTION_UNFREEZE_ALL,
                        getString(R.string.action_unfreeze_all),
                        Intent(HailApi.ACTION_UNFREEZE_ALL)
                    )

                    4 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_round_frozen_shortcut
                        )!!,
                        HailApi.ACTION_FREEZE_NON_WHITELISTED,
                        getString(R.string.action_freeze_non_whitelisted),
                        Intent(HailApi.ACTION_FREEZE_NON_WHITELISTED)
                    )

                    5 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!, HailApi.ACTION_LOCK, getString(R.string.action_lock), Intent(HailApi.ACTION_LOCK)
                    )

                    6 -> HShortcuts.addPinShortcut(
                        AppCompatResources.getDrawable(
                            requireContext(), R.drawable.ic_outline_lock_shortcut
                        )!!,
                        HailApi.ACTION_LOCK_FREEZE,
                        getString(R.string.action_lock_freeze),
                        Intent(HailApi.ACTION_LOCK_FREEZE)
                    )
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showFreezeOperationLog() {
        operations.run { EnhancedResult.Journal(FreezeControllerFacade.recentOperations(50)) }
    }

    private fun stateLabel(state: FreezeJournalState) = getString(when (state) {
        FreezeJournalState.PENDING -> R.string.state_pending
        FreezeJournalState.VERIFIED -> R.string.state_verified
        FreezeJournalState.FAILED -> R.string.state_failed
        FreezeJournalState.UNVERIFIED -> R.string.state_unverified
    })

    private fun displayJournal(entries: List<FreezeJournalEntry>) {
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.freeze_operation_log)
        if (entries.isEmpty()) builder.setMessage(R.string.freeze_operation_log_empty)
        else builder.setItems(entries.map { entry ->
            val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAtEpochMs))
            val action = getString(if (entry.action == com.aistra.hail.enhanced.domain.FreezeAction.FREEZE)
                R.string.rule_action_freeze else R.string.rule_action_unfreeze)
            "$time · ${entry.packageName} · $action · ${stateLabel(entry.state)}"
        }.toTypedArray()) { _, index ->
            val entry = entries[index]
            MaterialAlertDialogBuilder(requireContext()).setTitle(entry.packageName)
                .setMessage(listOfNotNull(entry.backendId, stateLabel(entry.state), entry.reason).joinToString("\n\n"))
                .setPositiveButton(android.R.string.ok, null).show()
        }
        builder.setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showProfilesDialog() {
        operations.run { EnhancedResult.Profiles(FreezeProfileStore(app).getAll()) }
    }

    private fun displayProfiles(profiles: List<FreezeProfile>) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.profile_manage)
            .setItems(profiles.map { it.name }.toTypedArray()) { _, index -> showProfileActions(profiles[index]) }
            .setPositiveButton(R.string.profile_create) { _, _ ->
                operations.run {
                    AppDataAccess.access {
                        val now = System.currentTimeMillis()
                        val states = HailData.checkedList.filter { it.packageName != app.packageName }
                            .associate { it.packageName to AppManager.isAppFrozen(it.packageName) }
                        EnhancedResult.ProfileDraft(FreezeProfile(java.util.UUID.randomUUID().toString(), "", states, now, now))
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showProfileActions(profile: FreezeProfile) {
        val actions = intArrayOf(R.string.profile_apply, R.string.profile_shortcut, R.string.profile_pin_shortcut,
            R.string.profile_delete, R.string.enhanced_edit).map(::getString).toTypedArray()
        MaterialAlertDialogBuilder(requireContext()).setTitle(profile.name).setItems(actions) { _, index ->
            when (index) {
                0 -> applyProfile(profile, false)
                1 -> HShortcuts.addProfileShortcut(profile)
                2 -> HShortcuts.addPinProfileShortcut(profile)
                3 -> MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.profile_delete).setMessage(profile.name)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        operations.run { FreezeProfileManager.delete(app, profile.id); EnhancedResult.Changed }
                    }.setNegativeButton(android.R.string.cancel, null).show()
                4 -> openEditor(EnhancedEditorDraft.forProfile(profile, false))
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun applyProfile(profile: FreezeProfile, allowConflicts: Boolean) {
        operations.run { EnhancedResult.Applied(FreezeProfileManager.apply(app, profile.id, allowConflicts), allowConflicts) }
    }

    private fun displayApplied(result: com.aistra.hail.app.FreezeProfileApplyResult, confirmed: Boolean) {
        if (result.conflicts > 0 && !confirmed) {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.profile_apply)
                .setMessage(getString(R.string.profile_conflicts, result.conflicts))
                .setPositiveButton(R.string.profile_apply_anyway) { _, _ -> applyProfile(result.profile, true) }
                .setNegativeButton(android.R.string.cancel, null).show()
        } else if (result.complete) HUI.showToast(R.string.profile_applied, result.profile.name)
        else {
            val details = result.results.filter { !it.stateVerified }.joinToString("\n\n") {
                it.packageName + "\n" + (it.reason ?: getString(R.string.state_unverified))
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.state_failed)
                .setMessage(getString(R.string.operation_partial_details) + "\n\n" + details)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun showRulesDialog() {
        operations.run { EnhancedResult.Rules(FreezeRuleStore(app).getRules()) }
    }

    private fun displayRules(rules: List<FreezeRule>) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.rule_manage)
            .setItems(rules.map { it.name + " · " + getString(if (it.enabled)
                R.string.editor_enabled else R.string.editor_disabled) }.toTypedArray()) { _, index -> showRuleActions(rules[index]) }
            .setPositiveButton(R.string.rule_add) { _, _ ->
                val rule = FreezeRule(java.util.UUID.randomUUID().toString(), "", true, RuleTrigger.SCREEN_OFF,
                    RuleAction.FREEZE, emptySet(), 30_000)
                openEditor(EnhancedEditorDraft.forRule(rule, true))
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showRuleActions(rule: FreezeRule) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(rule.name)
            .setItems(arrayOf(getString(R.string.enhanced_edit), getString(if (rule.enabled)
                R.string.editor_disabled else R.string.editor_enabled), getString(R.string.rule_delete))) { _, index ->
                when (index) {
                    0 -> openEditor(EnhancedEditorDraft.forRule(rule, false))
                    1 -> operations.run {
                        FreezeRuleStore(app).updateRules { current ->
                            check(current.firstOrNull { it.id == rule.id } == rule) { "Rule changed; reopen it" }
                            current.map { if (it.id == rule.id) it.copy(enabled = !it.enabled) else it }
                        }
                        EnhancedResult.Changed
                    }
                    2 -> MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.rule_delete).setMessage(rule.name)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            operations.run {
                                FreezeRuleStore(app).updateRules { current -> current.filterNot { it.id == rule.id } }
                                EnhancedResult.Changed
                            }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun openEditor(draft: EnhancedEditorDraft) {
        if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag("enhanced-editor") == null)
            EnhancedEditorDialogFragment.create(draft).show(childFragmentManager, "enhanced-editor")
    }

    private fun applyWorkingMode(state: MutableState<String>, mode: String, request: Long) {
        if (request != modeRequest || !isAdded || view == null) return
        AppDataAccess.tryAccess { state.value = mode }.onSuccess {
            activity.invalidateOptionsMenu()
            app.setAutoFreezeService()
        }.onFailure { HUI.showToast(R.string.operation_failed, HUI.operationError(it), true) }
    }

    fun onWorkingModeChange(rememberState: MutableState<String>, mode: String): Boolean {
        val request = ++modeRequest
        if (mode.startsWith(HailData.SU) || mode == HailData.MODE_JAILBREAK_DISABLE) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val allowed = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                        if (mode.startsWith(HailData.SU)) {
                            val checked = HShell.execute("whoami", true)
                            check(checked.first == 0) {
                                com.aistra.hail.enhanced.domain.FreezeFailureDiagnostics.shell(checked.first, checked.second)
                            }
                            true
                        } else {
                            if (ContextCompat.checkSelfPermission(app, CHANGE_COMPONENT_ENABLED_STATE) != PackageManager.PERMISSION_GRANTED) {
                                HShell.grantPermission(app.packageName, CHANGE_COMPONENT_ENABLED_STATE)
                            }
                            ContextCompat.checkSelfPermission(app, CHANGE_COMPONENT_ENABLED_STATE) == PackageManager.PERMISSION_GRANTED
                        }
                    }
                    if (request != modeRequest) return@launch
                    if (allowed) applyWorkingMode(rememberState, mode, request)
                    else HUI.showToast(R.string.permission_denied)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { HUI.showToast(R.string.operation_failed, HUI.operationError(error), true) }
            }
            return false
        }
        // Show/hide terminal menu.
        activity.invalidateOptionsMenu()
        when {
            mode.startsWith(HailData.OWNER) -> if (!HPolicy.isDeviceOwnerActive) {
                MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.title_set_owner)
                    .setMessage(getString(R.string.msg_set_owner, HPolicy.ADB_COMMAND))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(HPolicy.ADB_COMMAND) }.show()
                    .findViewById<MaterialTextView>(android.R.id.message)?.setTextIsSelectable(true)
                return false
            }

            mode.startsWith(HailData.DHIZUKU) -> return runCatching {
                Dhizuku.init(app)
                when {
                    Dhizuku.isPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                                    override fun onRequestPermission(grantResult: Int) {
                                        trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                    }
                                })
                                awaitClose()
                            }.first()
                            if (result) {
                                applyWorkingMode(rememberState, mode, request)
                                if (HTarget.O) HDhizuku.setDelegatedScopes()
                            }
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }.let { granted ->
                if (granted) applyWorkingMode(rememberState, mode, request)
                false
            }

            mode.startsWith(HailData.SHIZUKU) -> return runCatching {
                when {
                    Shizuku.isPreV11() -> throw IllegalStateException("unsupported shizuku version")
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
                    Shizuku.shouldShowRequestPermissionRationale() -> {
                        HUI.showToast(R.string.permission_denied)
                        false
                    }

                    else -> {
                        lifecycleScope.launch {
                            val result = callbackFlow {
                                val shizukuRequestCode = 0
                                val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                                    if (requestCode != shizukuRequestCode) return@OnRequestPermissionResultListener
                                    trySendBlocking(grantResult == PackageManager.PERMISSION_GRANTED)
                                }
                                Shizuku.addRequestPermissionResultListener(listener)
                                Shizuku.requestPermission(shizukuRequestCode)
                                awaitClose {
                                    Shizuku.removeRequestPermissionResultListener(listener)
                                }
                            }.first()
                            if (result) applyWorkingMode(rememberState, mode, request)
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.shizuku_missing)
                false
            }.let { granted ->
                if (granted) applyWorkingMode(rememberState, mode, request)
                false
            }

            mode.startsWith(HailData.ISLAND) -> return runCatching {
                when {
                    mode == HailData.MODE_ISLAND_HIDE && HIsland.freezePermissionGranted() -> true
                    mode == HailData.MODE_ISLAND_SUSPEND && HIsland.suspendPermissionGranted() -> true
                    else -> {
                        lifecycleScope.launch {
                            requestPermissionLauncher.launch(
                                if (mode == HailData.MODE_ISLAND_HIDE) HIsland.PERMISSION_FREEZE_PACKAGE
                                else HIsland.PERMISSION_SUSPEND_PACKAGE
                            )
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.permission_denied)
                false
            }.also {
                if (it) {
                    HIsland.checkOwnerApp()
                    applyWorkingMode(rememberState, mode, request)
                }
            }.let { false }

            mode.startsWith(HailData.PRIVAPP) -> if (!HPackages.isPrivilegedApp(app.packageName)) {
                HUI.showToast(R.string.permission_denied)
                return false
            }
        }

        applyWorkingMode(rememberState, mode, request)
        return false
    }

    private suspend fun onTerminalResult(exitValue: Int, msg: String?) = withContext(Dispatchers.Main) {
        if (exitValue == 0 && msg.isNullOrBlank()) return@withContext
        MaterialAlertDialogBuilder(requireActivity()).apply {
            if (!msg.isNullOrBlank()) {
                if (exitValue != 0) {
                    setTitle(getString(R.string.operation_failed, exitValue.toString()))
                }
                setMessage(msg)
                setNeutralButton(android.R.string.copy) { _, _ -> HUI.copyText(msg) }
            } else if (exitValue != 0) {
                setMessage(getString(R.string.operation_failed, exitValue.toString()))
            }
        }.setPositiveButton(android.R.string.ok, null).show().findViewById<MaterialTextView>(android.R.id.message)
            ?.setTextIsSelectable(true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_terminal -> showTerminalDialog()
            R.id.action_remove_owner -> (requireActivity() as MainActivity).ownerRemoveDialog()
            R.id.action_help -> HUI.openLink(HailData.URL_README)
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_settings, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        if (HailData.workingMode.startsWith(HailData.SU) || HailData.workingMode.startsWith(
                HailData.SHIZUKU
            )
        ) menu.findItem(R.id.action_terminal).isVisible = true
        else if (HPolicy.isDeviceOwnerActive) menu.findItem(R.id.action_remove_owner).isVisible = true
    }

    private fun showTerminalDialog() {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.command)
        binding.editText.run {
            setSingleLine()
            filters = arrayOf()
        }
        MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_terminal).setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val result = AppManager.execute(binding.editText.text.toString())
                    onTerminalResult(result.first, result.second)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}