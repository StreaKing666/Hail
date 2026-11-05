package com.aistra.hail.utils

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.enhanced.domain.ProfileShortcutIds
import com.aistra.hail.enhanced.domain.FreezeProfile
import me.zhanghai.android.appiconloader.AppIconLoader

object HShortcuts {
    private val iconLoader by lazy {
        AppIconLoader(
            app.resources.getDimensionPixelSize(R.dimen.app_icon_size),
            HailData.synthesizeAdaptiveIcons,
            app
        )
    }

    fun addPinShortcut(icon: Drawable, id: String, label: CharSequence, intent: Intent) {
        addPinShortcut(getDrawableIcon(icon), id, label, intent)
    }

    fun addPinShortcut(appInfo: AppInfo, id: String, label: CharSequence, intent: Intent) {
        appInfo.applicationInfo?.let {
            val icon = IconPack.loadIcon(it.packageName) ?: iconLoader.loadIcon(it)
            addPinShortcut(IconCompat.createWithBitmap(icon), id, label, intent)
        } ?: run {
            addPinShortcut(app.packageManager.defaultActivityIcon, id, label, intent)
        }
    }

    private fun addPinShortcut(icon: IconCompat, id: String, label: CharSequence, intent: Intent) {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(app)) {
            val shortcut =
                ShortcutInfoCompat.Builder(app, id).setIcon(icon).setShortLabel(label)
                    .setIntent(intent).build()
            ShortcutManagerCompat.requestPinShortcut(app, shortcut, null)
        } else HUI.showToast(
            R.string.operation_failed, app.getString(R.string.action_add_pin_shortcut)
        )
    }

    fun addDynamicShortcut(packageName: String) {
        if (HailData.biometricLogin) return
        val applicationInfo = HPackages.getApplicationInfoOrNull(packageName)
        val shortcut =
            ShortcutInfoCompat.Builder(app, packageName.hashCode().toString()) // Make id different from pin
                .setIcon(IconCompat.createWithBitmap(applicationInfo?.let {
                    IconPack.loadIcon(it.packageName) ?: iconLoader.loadIcon(it)
                } ?: getBitmapFromDrawable(
                    app.packageManager.defaultActivityIcon
                ))).setShortLabel(
                    applicationInfo?.loadLabel(app.packageManager) ?: packageName
                ).setIntent(HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, packageName)).build()
        ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
        addDynamicShortcutAction(HailData.dynamicShortcutAction)
    }

    fun addDynamicShortcutAction(action: String) {
        if (action == HailData.ACTION_NONE) return
        val id = when (action) {
            HailData.ACTION_FREEZE_ALL -> HailApi.ACTION_FREEZE_ALL
            HailData.ACTION_FREEZE_NON_WHITELISTED -> HailApi.ACTION_FREEZE_NON_WHITELISTED
            HailData.ACTION_LOCK -> HailApi.ACTION_LOCK
            HailData.ACTION_LOCK_FREEZE -> HailApi.ACTION_LOCK_FREEZE
            else -> HailApi.ACTION_UNFREEZE_ALL
        }
        val icon = when (action) {
            HailData.ACTION_FREEZE_ALL, HailData.ACTION_FREEZE_NON_WHITELISTED -> R.drawable.ic_round_frozen_shortcut
            HailData.ACTION_LOCK, HailData.ACTION_LOCK_FREEZE -> R.drawable.ic_outline_lock_shortcut
            else -> R.drawable.ic_round_unfrozen_shortcut
        }
        val label = when (action) {
            HailData.ACTION_FREEZE_ALL -> R.string.action_freeze_all
            HailData.ACTION_FREEZE_NON_WHITELISTED -> R.string.action_freeze_non_whitelisted
            HailData.ACTION_LOCK -> R.string.action_lock
            HailData.ACTION_LOCK_FREEZE -> R.string.action_lock_freeze
            else -> R.string.action_unfreeze_all
        }
        val shortcut = ShortcutInfoCompat.Builder(app, id).setIcon(
            getDrawableIcon(
                AppCompatResources.getDrawable(
                    app, icon
                )!!
            )
        ).setShortLabel(app.getString(label)).setIntent(Intent(id)).build()
        ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
    }

    internal fun addProfileShortcut(profile: FreezeProfile) {
        if (HailData.biometricLogin) return
        val shortcut = ShortcutInfoCompat.Builder(app, ProfileShortcutIds.dynamic(profile.id))
            .setIcon(
                getDrawableIcon(
                    AppCompatResources.getDrawable(app, R.drawable.ic_round_frozen_shortcut)!!
                )
            )
            .setShortLabel(profile.name.take(10))
            .setLongLabel(profile.name)
            .setIntent(HailApi.getIntentForProfile(profile.id))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
    }

    internal fun addPinProfileShortcut(profile: FreezeProfile) {
        addPinShortcut(
            AppCompatResources.getDrawable(app, R.drawable.ic_round_frozen_shortcut)!!,
            ProfileShortcutIds.pinned(profile.id),
            profile.name,
            HailApi.getIntentForProfile(profile.id)
        )
    }

    internal fun removeProfileShortcuts(profileId: String) {
        val ids = ProfileShortcutIds.all(profileId)
        // Launchers own pinned shortcuts: disable them instead of promising removal.
        ShortcutManagerCompat.disableShortcuts(app, ids, app.getString(R.string.profile_deleted))
        ShortcutManagerCompat.removeDynamicShortcuts(app, ids)
        ShortcutManagerCompat.removeLongLivedShortcuts(app, ids)
    }

    internal fun refreshProfileShortcuts(profile: FreezeProfile) {
        // updateShortcuts changes existing pinned/dynamic entries, without creating new ones.
        ShortcutManagerCompat.updateShortcuts(app, ProfileShortcutIds.all(profile.id).map { id ->
            ShortcutInfoCompat.Builder(app, id)
                .setShortLabel(profile.name.take(40)).setLongLabel(profile.name)
                .setIcon(getDrawableIcon(AppCompatResources.getDrawable(app, R.drawable.ic_round_frozen_shortcut)!!))
                .setIntent(HailApi.getIntentForProfile(profile.id)).build()
        })
    }

    internal fun refreshTagShortcuts(oldName: String, newName: String): Boolean = runCatching {
        val shortcuts = ShortcutManagerCompat.getShortcuts(app,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED)
        val updates = shortcuts.distinctBy { it.id }.mapNotNull { shortcut ->
            if (shortcut.intents.none { it.getStringExtra(HailData.KEY_TAG) == oldName }) return@mapNotNull null
            val intents = shortcut.intents.map { original ->
                Intent(original).apply {
                    if (getStringExtra(HailData.KEY_TAG) == oldName) putExtra(HailData.KEY_TAG, newName)
                }
            }.toTypedArray()
            shortcutUpdate(shortcut).setIntents(intents).apply {
                if (shortcut.intent.action in listOf(HailApi.ACTION_FREEZE_TAG, HailApi.ACTION_UNFREEZE_TAG)) {
                    setShortLabel(newName.take(40)); setLongLabel(newName)
                }
            }.build()
        }
        updates.isEmpty() || ShortcutManagerCompat.updateShortcuts(app, updates)
    }.getOrElse { HLog.e(it); false }

    internal fun removeTagShortcuts(tagName: String): Boolean = runCatching {
        val shortcuts = ShortcutManagerCompat.getShortcuts(app,
            ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .distinctBy { it.id }.filter { shortcut ->
                shortcut.intents.any { it.getStringExtra(HailData.KEY_TAG) == tagName }
            }
        val tagActions = shortcuts.filter { it.intent.action in
            listOf(HailApi.ACTION_FREEZE_TAG, HailApi.ACTION_UNFREEZE_TAG) }.map { it.id }
        if (tagActions.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(app, tagActions, app.getString(R.string.operation_failed, tagName))
            ShortcutManagerCompat.removeDynamicShortcuts(app, tagActions)
            ShortcutManagerCompat.removeLongLivedShortcuts(app, tagActions)
        }
        val launches = shortcuts.filterNot { it.id in tagActions }.map { shortcut ->
            shortcutUpdate(shortcut).setIntents(shortcut.intents.map { original ->
                Intent(original).apply {
                    if (getStringExtra(HailData.KEY_TAG) == tagName) removeExtra(HailData.KEY_TAG)
                }
            }.toTypedArray()).build()
        }
        launches.isEmpty() || ShortcutManagerCompat.updateShortcuts(app, launches)
    }.getOrElse { HLog.e(it); false }

    /** Public partial-update API; omitted icons remain owned by the existing shortcut. */
    private fun shortcutUpdate(shortcut: ShortcutInfoCompat): ShortcutInfoCompat.Builder =
        ShortcutInfoCompat.Builder(app, shortcut.id)
            .setShortLabel(shortcut.shortLabel)
            .setRank(shortcut.rank)
            .apply {
                shortcut.longLabel?.let { setLongLabel(it) }
                shortcut.activity?.let { setActivity(it) }
                shortcut.extras?.let { setExtras(android.os.PersistableBundle(it)) }
            }

    fun removeAllDynamicShortcuts() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(app)
    }

    private fun getDrawableIcon(drawable: Drawable): IconCompat =
        IconCompat.createWithBitmap(getBitmapFromDrawable(drawable))

    private fun getBitmapFromDrawable(drawable: Drawable): Bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888
    ).also {
        with(Canvas(it)) {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(this)
        }
    }
}
