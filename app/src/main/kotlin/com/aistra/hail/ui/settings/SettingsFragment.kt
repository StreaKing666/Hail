package com.aistra.hail.ui.settings

import android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.EditText
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
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.app.FreezeProfileManager
import com.aistra.hail.app.FreezeProfileStore
import com.aistra.hail.app.FreezeRuleStore
import com.aistra.hail.app.FreezeControllerFacade
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
                    FreezeRuleStore(requireContext()).isEnabled = value
                    true
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
        val entries = FreezeControllerFacade.recentOperations(50)
        if (entries.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.freeze_operation_log)
                .setMessage(R.string.freeze_operation_log_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val rows = entries.map { entry ->
            val time = java.text.DateFormat.getDateTimeInstance()
                .format(java.util.Date(entry.createdAtEpochMs))
            time + " · " + entry.packageName + " · " + entry.action.name + " · " + entry.state.name
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.freeze_operation_log)
            .setItems(rows) { _, index ->
                val entry = entries[index]
                val detail = buildString {
                    append(entry.backendId)
                    append("\n")
                    append(entry.state.name)
                    entry.reason?.let {
                        append("\n\n")
                        append(it)
                    }
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(entry.packageName)
                    .setMessage(detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showProfilesDialog() {
        val profiles = FreezeProfileStore(requireContext()).getAll()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_manage)
            .setItems(profiles.map { it.name }.toTypedArray()) { _, index ->
                profiles.getOrNull(index)?.let(::showProfileActions)
            }
            .setPositiveButton(R.string.profile_create) { _, _ -> promptProfileName() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptProfileName() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.profile_name)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_create)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                runCatching {
                    FreezeProfileManager.createFromTracked(requireContext(), input.text.toString())
                }.onSuccess {
                    HShortcuts.addProfileShortcut(it)
                    HUI.showToast(R.string.profile_created)
                }.onFailure {
                    HUI.showToast(R.string.operation_failed, it.message ?: "Unknown", true)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileActions(profile: FreezeProfile) {
        val actions = arrayOf(
            getString(R.string.profile_apply),
            getString(R.string.profile_shortcut),
            getString(R.string.profile_pin_shortcut),
            getString(R.string.profile_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(profile.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> applyProfile(profile, false)
                    1 -> {
                        HShortcuts.addProfileShortcut(profile)
                        HUI.showToast(R.string.profile_created)
                    }
                    2 -> HShortcuts.addPinProfileShortcut(profile)
                    3 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.profile_delete)
                        .setMessage(profile.name)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            FreezeProfileManager.delete(requireContext(), profile.id)
                            HUI.showToast(R.string.profile_deleted)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyProfile(profile: FreezeProfile, allowConflicts: Boolean) {
        runCatching {
            FreezeProfileManager.apply(requireContext(), profile.id, allowConflicts)
        }.onSuccess { result ->
            if (result.conflicts > 0 && !allowConflicts) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.profile_apply)
                    .setMessage(getString(R.string.profile_conflicts, result.conflicts))
                    .setPositiveButton(R.string.profile_apply_anyway) { _, _ ->
                        applyProfile(profile, true)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else if (result.complete) {
                HUI.showToast(R.string.profile_applied, profile.name)
            } else {
                HUI.showToast(R.string.permission_denied)
            }
        }.onFailure {
            HUI.showToast(R.string.operation_failed, it.message ?: "Unknown", true)
        }
    }

    private fun showRulesDialog() {
        val store = FreezeRuleStore(requireContext())
        val rules = store.getRules()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rule_manage)
            .setItems(rules.map { it.name + if (it.enabled) " ✓" else " (off)" }.toTypedArray()) { _, index ->
                rules.getOrNull(index)?.let(::showRuleActions)
            }
            .setPositiveButton(R.string.rule_add) { _, _ -> chooseRuleTrigger() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseRuleTrigger() {
        val triggers = arrayOf(
            getString(R.string.rule_trigger_screen_off),
            getString(R.string.rule_trigger_charging),
            getString(R.string.rule_trigger_not_charging)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rule_add)
            .setItems(triggers) { _, which ->
                val trigger = when (which) {
                    0 -> RuleTrigger.SCREEN_OFF
                    1 -> RuleTrigger.CHARGING
                    else -> RuleTrigger.NOT_CHARGING
                }
                chooseRuleAction(trigger)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseRuleAction(trigger: RuleTrigger) {
        val actions = arrayOf(
            getString(R.string.rule_action_freeze),
            getString(R.string.rule_action_unfreeze)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rule_add)
            .setItems(actions) { _, which ->
                createRule(trigger, if (which == 0) RuleAction.FREEZE else RuleAction.UNFREEZE)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createRule(trigger: RuleTrigger, action: RuleAction) {
        val packages = HailData.checkedList
            .filter { it.packageName != app.packageName }
            .map { it.packageName }
            .toSet()
        if (packages.isEmpty()) {
            HUI.showToast(R.string.rule_no_tracked_apps)
            return
        }
        val triggerName = when (trigger) {
            RuleTrigger.SCREEN_OFF -> getString(R.string.rule_trigger_screen_off)
            RuleTrigger.CHARGING -> getString(R.string.rule_trigger_charging)
            RuleTrigger.NOT_CHARGING -> getString(R.string.rule_trigger_not_charging)
        }
        val actionName = if (action == RuleAction.FREEZE) {
            getString(R.string.rule_action_freeze)
        } else getString(R.string.rule_action_unfreeze)
        val store = FreezeRuleStore(requireContext())
        val rule = FreezeRule(
            id = UUID.randomUUID().toString(),
            name = triggerName + " — " + actionName,
            enabled = true,
            trigger = trigger,
            action = action,
            packageNames = packages,
            cooldownMillis = 30_000L
        )
        runCatching { store.saveRules(store.getRules() + rule) }
            .onSuccess { HUI.showToast(R.string.rule_created) }
            .onFailure { HUI.showToast(R.string.operation_failed, it.message ?: "Unknown", true) }
    }

    private fun showRuleActions(rule: FreezeRule) {
        val store = FreezeRuleStore(requireContext())
        val actions = arrayOf(getString(R.string.rule_toggle), getString(R.string.rule_delete))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(rule.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        store.saveRules(store.getRules().map {
                            if (it.id == rule.id) it.copy(enabled = !it.enabled) else it
                        })
                    }
                    1 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.rule_delete)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            store.saveRules(store.getRules().filterNot { it.id == rule.id })
                            HUI.showToast(R.string.rule_deleted)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun onWorkingModeChange(rememberState: MutableState<String>, mode: String): Boolean {
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
                                rememberState.value = mode
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
            }

            mode.startsWith(HailData.SU) -> if (!HShell.checkSU) {
                HUI.showToast(R.string.permission_denied)
                return false
            }

            mode == HailData.MODE_JAILBREAK_DISABLE -> if (
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    CHANGE_COMPONENT_ENABLED_STATE
                ) == PackageManager.PERMISSION_DENIED
                && !HShell.grantPermission(requireContext().packageName, CHANGE_COMPONENT_ENABLED_STATE)
            ) {
                HUI.showToast(R.string.permission_denied)
                return false
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
                            if (result) rememberState.value = mode
                        }
                        false
                    }
                }
            }.getOrElse {
                HLog.e(it)
                HUI.showToast(R.string.shizuku_missing)
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
                }
            }

            mode.startsWith(HailData.PRIVAPP) -> if (!HPackages.isPrivilegedApp(app.packageName)) {
                HUI.showToast(R.string.permission_denied)
                return false
            }
        }

        return true
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