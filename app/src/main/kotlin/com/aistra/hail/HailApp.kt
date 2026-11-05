package com.aistra.hail

import android.app.Application
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.FullBackupStorage
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.app.FreezeRuleStore
import com.aistra.hail.app.RuleServicePolicy
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import com.aistra.hail.app.HailData
import com.aistra.hail.services.AutoFreezeService
import com.aistra.hail.utils.HDhizuku
import com.aistra.hail.utils.HTarget

class HailApp : Application() {
    private val serviceRevision = java.util.concurrent.atomic.AtomicLong()
    private val recoveryRunning = AtomicBoolean(false)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        app = this
        // DirtyDataUpdater.update(app)
        if (!HTarget.S) setAppTheme(HailData.appTheme)
        if (HailData.workingMode.startsWith(HailData.DHIZUKU)) HDhizuku.init()
        // Reconcile interrupted operations from the persistent journal. Never replay commands.
        retryRecovery()
    }

    fun retryRecovery() {
        if (!recoveryRunning.compareAndSet(false, true)) return
        applicationScope.launch {
            try {
                AppDataAccess.recover {
                    FullBackupStorage.recoverInterruptedRestore()
                    AppManager.recoverPendingFreezeOperations()
                    HailData.validateLoadedState()
                }
            } catch (error: Exception) {
                // No user data, commands, paths or exception messages in the diagnostic.
                android.util.Log.e("HailRecovery", "Recovery failed: " + error.javaClass.simpleName)
            } finally { recoveryRunning.set(false) }
        }
    }

    fun setAutoFreezeService(autoFreezeAfterLock: Boolean = HailData.autoFreezeAfterLock, context: Context = app) {
        val revision = serviceRevision.incrementAndGet()
        val applicationContext = context.applicationContext
        applicationScope.launch {
            try {
                val start = AppDataAccess.access {
                    val eligible = HailData.checkedList.any {
                        it.packageName != packageName && it.applicationInfo != null &&
                            !AppManager.isAppFrozen(it.packageName) && !it.whitelisted
                    }
                    val rules = FreezeRuleStore(this@HailApp)
                    RuleServicePolicy.needsScreenReceiver(autoFreezeAfterLock, eligible,
                        rules.isEnabled, if (rules.isEnabled) rules.getRules() else emptyList())
                }
                withContext(Dispatchers.Main) {
                    if (revision != serviceRevision.get()) return@withContext
                    val intent = Intent(app, AutoFreezeService::class.java)
                    runCatching {
                        if (start) {
                            setAutoFreezeServiceEnabled(true)
                            ContextCompat.startForegroundService(applicationContext, intent)
                        } else {
                            stopService(intent)
                            setAutoFreezeServiceEnabled(false)
                        }
                    }.onFailure {
                        android.util.Log.e("HailService", "Listener update failed: " + it.javaClass.simpleName)
                        com.aistra.hail.utils.HUI.showToast(com.aistra.hail.R.string.rule_service_failed)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("HailService", "Listener policy unavailable: " + error.javaClass.simpleName)
            }
        }
    }

    fun setAutoFreezeServiceEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            ComponentName(app, AutoFreezeService::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    fun setAppTheme(theme: String) {
        if (HTarget.S) getSystemService<UiModeManager>()!!.setApplicationNightMode(
            when (theme) {
                HailData.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                HailData.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            }
        )
        else AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                HailData.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                HailData.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }


    companion object {
        lateinit var app: HailApp private set
    }
}
