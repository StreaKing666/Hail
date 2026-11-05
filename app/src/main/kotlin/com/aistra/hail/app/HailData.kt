package com.aistra.hail.app

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.utils.HFiles
import org.json.JSONArray
import org.json.JSONObject

object HailData {
    const val URL_WHY_FREE_SOFTWARE = "https://www.gnu.org/philosophy/free-software-even-more-important.html"
    const val URL_GITHUB = "https://github.com/StreaKing666/Hail"
    const val URL_README = "$URL_GITHUB#readme"
    const val URL_RELEASES = "$URL_GITHUB/releases"
    const val URL_TELEGRAM = "https://t.me/+yvRXYTounDIxODFl"
    const val URL_QQ = "http://qm.qq.com/cgi-bin/qm/qr?k=I2g_Ymanc6bQMo4cVKTG0knARE0twtSG"
    const val URL_FDROID = "https://f-droid.org/packages/com.aistra.hail"
    const val URL_ALIPAY = "https://qr.alipay.com/tsx02922ajwj6xekqyd1rbf"
    const val URL_ALIPAY_API = "alipays://platformapi/startapp?saId=10000007&qrcode=$URL_ALIPAY"
    const val URL_KOFI = "https://ko-fi.com/aistra0528"
    const val URL_LIBERAPAY = "https://liberapay.com/aistra0528"
    const val URL_PAYPAL = "https://www.paypal.me/aistra0528"
    const val URL_TRANSLATE = "https://hosted.weblate.org/engage/hail/"
    const val VERSION = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    private const val KEY_ID = "id"
    const val KEY_TAG = "tag"
    private const val KEY_TAGS = "tags"
    private const val KEY_PINNED = "pinned"
    private const val KEY_WHITELISTED = "whitelisted"
    const val KEY_PACKAGE = "package"
    const val KEY_FROZEN = "frozen"
    private const val SORT_BY = "sort_by"
    const val SORT_NAME = "name"
    const val SORT_INSTALL = "install"
    const val SORT_UPDATE = "update"
    const val FILTER_USER_APPS = "filter_user_apps"
    const val FILTER_SYSTEM_APPS = "filter_system_apps"
    const val FILTER_FROZEN_APPS = "filter_frozen_apps"
    const val FILTER_UNFROZEN_APPS = "filter_unfrozen_apps"
    const val FILTER_DISABLED_APPS = "filter_disabled_apps"
    const val FILTER_HIDDEN_APPS = "filter_hidden_apps"
    const val OWNER = "owner_"
    const val DHIZUKU = "dhizuku_"
    const val SU = "su_"
    const val JAILBREAK = "jailbreak_"
    const val SHIZUKU = "shizuku_"
    const val ISLAND = "island_"
    const val PRIVAPP = "privapp_"
    const val STOP = "stop"
    const val DISABLE = "disable"
    const val HIDE = "hide"
    const val SUSPEND = "suspend"
    const val WORKING_MODE = "working_mode"
    const val MODE_DEFAULT = "default"
    const val MODE_SHIZUKU_STOP = SHIZUKU + STOP
    const val MODE_SHIZUKU_DISABLE = SHIZUKU + DISABLE
    const val MODE_SHIZUKU_HIDE = SHIZUKU + HIDE
    const val MODE_SHIZUKU_SUSPEND = SHIZUKU + SUSPEND
    const val MODE_SU_STOP = SU + STOP
    const val MODE_SU_DISABLE = SU + DISABLE
    const val MODE_SU_HIDE = SU + HIDE
    const val MODE_SU_SUSPEND = SU + SUSPEND
    const val MODE_JAILBREAK_DISABLE = JAILBREAK + DISABLE
    const val MODE_DHIZUKU_HIDE = DHIZUKU + HIDE
    const val MODE_DHIZUKU_SUSPEND = DHIZUKU + SUSPEND
    const val MODE_OWNER_HIDE = OWNER + HIDE
    const val MODE_OWNER_SUSPEND = OWNER + SUSPEND
    const val MODE_ISLAND_HIDE = ISLAND + HIDE
    const val MODE_ISLAND_SUSPEND = ISLAND + SUSPEND
    const val MODE_PRIVAPP_STOP = PRIVAPP + STOP
    const val MODE_PRIVAPP_DISABLE = PRIVAPP + DISABLE
    val WORKING_MODE_VALUES = listOf(
        MODE_DEFAULT,
        MODE_SHIZUKU_STOP,
        MODE_SHIZUKU_DISABLE,
        MODE_SHIZUKU_HIDE,
        MODE_SHIZUKU_SUSPEND,
        MODE_SU_STOP,
        MODE_SU_DISABLE,
        MODE_SU_HIDE,
        MODE_SU_SUSPEND,
        MODE_JAILBREAK_DISABLE,
        MODE_DHIZUKU_HIDE,
        MODE_DHIZUKU_SUSPEND,
        MODE_OWNER_HIDE,
        MODE_OWNER_SUSPEND,
        MODE_ISLAND_HIDE,
        MODE_ISLAND_SUSPEND,
        MODE_PRIVAPP_STOP,
        MODE_PRIVAPP_DISABLE
    )
    const val BIOMETRIC_LOGIN = "biometric_login"
    const val APP_THEME = "app_theme"
    const val FOLLOW_SYSTEM = "follow_system"
    const val THEME_LIGHT = "theme_light"
    const val THEME_DARK = "theme_dark"
    val APP_THEME_VALUES = listOf(FOLLOW_SYSTEM, THEME_LIGHT, THEME_DARK)
    const val ICON_PACK = "icon_pack"
    const val GRAYSCALE_ICON = "grayscale_icon"
    const val COMPACT_ICON = "compact_icon"
    const val SYNTHESIZE_ADAPTIVE_ICONS = "synthesize_adaptive_icons"
    const val HOME_FONT_SIZE = "home_font_size_f"
    const val FUZZY_SEARCH = "fuzzy_search"
    const val NINE_KEY_SEARCH = "nine_key"
    const val TILE_ACTION = "tile_action"
    const val ACTION_NONE = "none"
    const val ACTION_FREEZE_ALL = "freeze_all"
    const val ACTION_UNFREEZE_ALL = "unfreeze_all"
    const val ACTION_FREEZE_NON_WHITELISTED = "freeze_non_whitelisted"
    const val ACTION_LOCK = "lock"
    const val ACTION_LOCK_FREEZE = "lock_freeze"
    val TILE_ACTION_VALUES =
        listOf(
            AUTO_FREEZE_AFTER_LOCK,
            ACTION_FREEZE_ALL,
            ACTION_UNFREEZE_ALL,
            ACTION_FREEZE_NON_WHITELISTED,
            ACTION_LOCK,
            ACTION_LOCK_FREEZE
        )
    const val AUTO_FREEZE_AFTER_LOCK = "auto_freeze_after_lock"
    const val AUTO_FREEZE_DELAY = "auto_freeze_delay_f"
    const val SKIP_WHILE_CHARGING = "skip_while_charging"
    const val SKIP_FOREGROUND_APP = "skip_foreground_app"
    const val SKIP_NOTIFYING_APP = "skip_notifying_app"
    const val DYNAMIC_SHORTCUT_ACTION = "dynamic_shortcut_action"
    val DYNAMIC_SHORTCUT_ACTIONS = listOf(
        ACTION_NONE,
        ACTION_FREEZE_ALL,
        ACTION_UNFREEZE_ALL,
        ACTION_FREEZE_NON_WHITELISTED,
        ACTION_LOCK,
        ACTION_LOCK_FREEZE
    )

    const val NAVIGATION_LAYOUT = "navigation_layout"
    const val NAV_AUTO = "auto"
    const val NAV_BOTTOM = "bottom"
    const val NAV_RAIL = "rail"
    const val NAV_BOTTOM_COMPACT = "bottom_compact"
    const val NAV_RAIL_COMPACT = "rail_compact"
    val NAVIGATION_LAYOUT_VALUES = listOf(
        NAV_AUTO, NAV_BOTTOM, NAV_RAIL, NAV_BOTTOM_COMPACT, NAV_RAIL_COMPACT
    )

    const val APP_LIST_LAYOUT = "app_list_layout"
    const val APP_LIST_STANDARD = "standard"
    const val APP_LIST_COMPACT = "compact"
    const val APP_LIST_TWO_COLUMNS = "two_columns"
    const val APP_LIST_THREE_COLUMNS = "three_columns"
    const val APP_LIST_FOUR_COLUMNS = "four_columns"
    val APP_LIST_LAYOUT_VALUES = listOf(
        APP_LIST_STANDARD, APP_LIST_COMPACT, APP_LIST_TWO_COLUMNS,
        APP_LIST_THREE_COLUMNS, APP_LIST_FOUR_COLUMNS
    )
    const val RULE_ENGINE_ENABLED = "rule_engine_enabled"
    const val ACTIVE_PROFILE_ID = "active_profile_id"

    private val sp = PreferenceManager.getDefaultSharedPreferences(app)
    val sortBy get() = sp.getString(SORT_BY, SORT_NAME)
    val filterUserApps get() = sp.getBoolean(FILTER_USER_APPS, true)
    val filterSystemApps get() = sp.getBoolean(FILTER_SYSTEM_APPS, false)
    val filterFrozenApps get() = sp.getBoolean(FILTER_FROZEN_APPS, true)
    val filterUnfrozenApps get() = sp.getBoolean(FILTER_UNFROZEN_APPS, true)
    val filterDisabledApps get() = sp.getBoolean(FILTER_DISABLED_APPS, true)
    val filterHiddenApps get() = sp.getBoolean(FILTER_HIDDEN_APPS, true)
    val workingMode get() = sp.getString(WORKING_MODE, MODE_DEFAULT)!!
    val biometricLogin get() = sp.getBoolean(BIOMETRIC_LOGIN, false)
    val appTheme get() = sp.getString(APP_THEME, FOLLOW_SYSTEM)!!
    val iconPack get() = sp.getString(ICON_PACK, ACTION_NONE)!!
    val grayscaleIcon get() = sp.getBoolean(GRAYSCALE_ICON, true)
    val compactIcon get() = sp.getBoolean(COMPACT_ICON, false)
    val synthesizeAdaptiveIcons get() = sp.getBoolean(SYNTHESIZE_ADAPTIVE_ICONS, false)
    val homeFontSize get() = sp.getFloat(HOME_FONT_SIZE, 14f)
    val fuzzySearch get() = sp.getBoolean(FUZZY_SEARCH, false)
    val nineKeySearch get() = sp.getBoolean(NINE_KEY_SEARCH, false)
    val tileAction get() = sp.getString(TILE_ACTION, AUTO_FREEZE_AFTER_LOCK)!!
    var autoFreezeAfterLock
        get() = sp.getBoolean(AUTO_FREEZE_AFTER_LOCK, false)
        set(value) = sp.edit { putBoolean(AUTO_FREEZE_AFTER_LOCK, value) }
    val autoFreezeDelay get() = sp.getFloat(AUTO_FREEZE_DELAY, 0f).toLong()
    val skipWhileCharging get() = sp.getBoolean(SKIP_WHILE_CHARGING, false)
    val skipForegroundApp get() = sp.getBoolean(SKIP_FOREGROUND_APP, false)
    val skipNotifyingApp get() = sp.getBoolean(SKIP_NOTIFYING_APP, false)
    val dynamicShortcutAction get() = sp.getString(DYNAMIC_SHORTCUT_ACTION, ACTION_NONE)!!
    val navigationLayout get() = sp.getString(NAVIGATION_LAYOUT, NAV_AUTO)!!
    val appListLayout get() = sp.getString(APP_LIST_LAYOUT, APP_LIST_STANDARD)!!
    val appListSpanCount get() = when (appListLayout) {
        APP_LIST_TWO_COLUMNS -> 2
        APP_LIST_THREE_COLUMNS -> 3
        APP_LIST_FOUR_COLUMNS -> 4
        else -> 1
    }
    val compactAppList get() = appListLayout == APP_LIST_COMPACT

    private val dir = "${app.filesDir.path}/v1"
    private val appsPath = "$dir/apps.json"
    private val tagsPath = "$dir/tags.json"

    val checkedList: MutableList<AppInfo> by lazy {
        mutableListOf<AppInfo>().apply {
            runCatching {
                val json = JSONArray(HFiles.read(appsPath))
                for (i in 0 until json.length()) {
                    add(with(json.getJSONObject(i)) {
                        AppInfo(
                            packageName = getString(KEY_PACKAGE),
                            pinned = optBoolean(KEY_PINNED),
                            whitelisted = optBoolean(KEY_WHITELISTED),
                            tagIdList = optJSONArray(KEY_TAGS)?.let {
                                MutableList(it.length()) { index -> it.getInt(index) }
                            } ?: mutableListOf(optInt(KEY_TAG))
                        )
                    })
                }
            }
        }
    }

    fun isChecked(packageName: String): Boolean = checkedList.any { it.packageName == packageName }

    fun addCheckedApp(packageName: String, tagId: Int = 0, saveApps: Boolean = true) = AppDataAccess.access {
        checkedList.add(AppInfo(packageName, tagIdList = mutableListOf(tagId)))
        if (saveApps) saveApps()
    }

    fun removeCheckedApp(packageName: String, saveApps: Boolean = true) = AppDataAccess.access {
        checkedList.removeAll { it.packageName == packageName }
        if (saveApps) saveApps()
    }

    fun saveApps() = AppDataAccess.access {
        if (!HFiles.exists(dir)) HFiles.createDirectories(dir)
        check(HFiles.write(appsPath, JSONArray().run {
            checkedList.forEach {
                put(
                    JSONObject()
                        .put(KEY_PACKAGE, it.packageName)
                        .put(KEY_PINNED, it.pinned)
                        .put(KEY_WHITELISTED, it.whitelisted)
                        .put(KEY_TAGS, JSONArray(it.tagIdList))
                )
            }
            toString()
        })) { "Could not save apps.json" }
    }

    val tags: MutableList<Pair<String, Int>> by lazy {
        mutableListOf<Pair<String, Int>>().apply {
            runCatching {
                val json = JSONArray(HFiles.read(tagsPath))
                for (i in 0 until json.length()) {
                    add(with(json.getJSONObject(i)) { getString(KEY_TAG) to getInt(KEY_ID) })
                }
            }.onFailure {
                add(app.getString(R.string.label_default) to 0)
            }
        }
    }

    fun saveTags() = AppDataAccess.access {
        if (!HFiles.exists(dir)) HFiles.createDirectories(dir)
        check(HFiles.write(tagsPath, JSONArray().run {
            tags.forEach {
                put(JSONObject().put(KEY_TAG, it.first).put(KEY_ID, it.second))
            }
            toString()
        })) { "Could not save tags.json" }
    }

    internal fun createTagBackup(): TagBackup = TagBackup(
        tags = tags.map { TagBackupTag(it.first, it.second) },
        apps = checkedList.map {
            TagBackupApp(it.packageName, it.tagIdList.toList(), it.pinned, it.whitelisted)
        }
    )

    internal fun importTagBackup(backup: TagBackup) = AppDataAccess.access {
        val existing = checkedList.map {
            TagBackupAppState(it.packageName, it.pinned, it.whitelisted, it.tagIdList.toList())
        }
        val plan = TagBackupMerger.merge(backup, existing)
        val previousTags = tags.toList()
        val previousApps = checkedList.toList()

        try {
            tags.clear()
            tags.addAll(plan.tags.map { it.name to it.id })
            checkedList.clear()
            plan.apps.forEach {
                checkedList.add(
                    AppInfo(
                        packageName = it.packageName,
                        pinned = it.pinned,
                        whitelisted = it.whitelisted,
                        tagIdList = it.tagIds.toMutableList()
                    )
                )
            }

            saveTags()
            saveApps()
        } catch (error: Exception) {
            // Restore the in-memory state and make a best-effort rollback of both files.
            tags.clear()
            tags.addAll(previousTags)
            checkedList.clear()
            checkedList.addAll(previousApps)
            runCatching {
                saveTags()
                saveApps()
            }
            throw error
        }
    }

    /**
     * Full restore replaces the tracked app/tag state exactly. Unlike the tag-only importer,
     * it also restores pin and whitelist flags from the archive.
     */
    internal fun restoreFullBackup(backup: TagBackup) = AppDataAccess.access {
        val valid = TagBackupArchive.normalizeAndValidate(backup)
        val previousTags = tags.toList()
        val previousApps = checkedList.map {
            AppInfo(
                packageName = it.packageName,
                pinned = it.pinned,
                whitelisted = it.whitelisted,
                tagIdList = it.tagIdList.toMutableList()
            )
        }

        try {
            tags.clear()
            tags.addAll(valid.tags.map { it.name to it.id })
            checkedList.clear()
            valid.apps.forEach {
                checkedList.add(
                    AppInfo(
                        packageName = it.packageName,
                        pinned = it.pinned,
                        whitelisted = it.whitelisted,
                        tagIdList = it.tagIds.toMutableList()
                    )
                )
            }
            saveTags()
            saveApps()
        } catch (error: Exception) {
            tags.clear()
            tags.addAll(previousTags)
            checkedList.clear()
            checkedList.addAll(previousApps)
            runCatching {
                saveTags()
                saveApps()
            }
            throw error
        }
    }

    fun changeAppsSort(sort: String) = sp.edit { putString(SORT_BY, sort) }

    fun changeAppsFilter(filter: String, enabled: Boolean) = sp.edit { putBoolean(filter, enabled) }
}