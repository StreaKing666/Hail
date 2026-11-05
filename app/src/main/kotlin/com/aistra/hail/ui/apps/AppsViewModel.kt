package com.aistra.hail.ui.apps

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.aistra.hail.HailApp
import com.aistra.hail.app.FreezePresentationPolicy
import com.aistra.hail.app.observeFrozenState
import com.aistra.hail.app.DiscoveredApp
import com.aistra.hail.app.HailData
import com.aistra.hail.app.PackageDiscoveryRepository
import com.aistra.hail.utils.*
import kotlinx.coroutines.*

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    val apps = MutableLiveData<List<ApplicationInfo>>()
    internal val discovery = MutableLiveData<List<DiscoveredApp>>()
    val isRefreshing = MutableLiveData(false)
    val query = MutableLiveData("")
    val displayApps = MutableLiveData<List<ApplicationInfo>>()
    private val mutableError = MutableLiveData<String?>()
    val error: LiveData<String?> = mutableError

    // Initialize every job/revision before the first inventory can run from init.
    private var queryJob: Job? = null
    private var refreshStateJob: Job? = null
    private var inventoryJob: Job? = null
    private var filterJob: Job? = null
    private var inventoryRevision = 0L
    private var filterRevision = 0L
    private var refreshRevision = 0L
    private var inventoryBusy = false
    private var filterBusy = false

    init {
        updateAppList()
    }

    /** Delay the spinner, and keep it active while either current operation runs. */
    private fun updateRefreshState() {
        val revision = ++refreshRevision
        refreshStateJob?.cancel()
        if (!inventoryBusy && !filterBusy) {
            isRefreshing.value = false
        } else if (isRefreshing.value != true) {
            refreshStateJob = viewModelScope.launch {
                delay(200L)
                if (revision == refreshRevision && (inventoryBusy || filterBusy)) {
                    isRefreshing.value = true
                }
            }
        }
    }

    fun clearError() {
        mutableError.value = null
    }

    fun postQuery(text: String, delayTime: Long = 300L) {
        queryJob?.cancel()
        if (delayTime == 0L)
            query.value = text
        else {
            queryJob = viewModelScope.launch {
                delay(delayTime)
                query.value = text
            }
        }
    }

    /** Refresh installed applications without changing filters or sorting. */
    fun updateAppList() {
        val revision = ++inventoryRevision
        inventoryJob?.cancel()
        inventoryBusy = true
        updateRefreshState()
        inventoryJob = viewModelScope.launch {
            try {
                val inventory = runInterruptible(Dispatchers.IO) {
                    PackageDiscoveryRepository(getApplication<HailApp>()).queryCurrentUser()
                }
                ensureActive()
                if (revision == inventoryRevision) {
                    discovery.value = inventory
                    apps.value = inventory.map { it.applicationInfo }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision == inventoryRevision) mutableError.value = HUI.operationError(error)
            } finally {
                // A cancelled older query must not stop the newer query's spinner.
                if (revision == inventoryRevision) {
                    inventoryBusy = false
                    updateRefreshState()
                }
            }
        }
    }

    /** Filter and sort an immutable snapshot; only the newest request may publish it. */
    fun updateDisplayAppList() {
        val appList = apps.value?.toList() ?: return
        val queryText = query.value
        val inventory = discovery.value.orEmpty().toList()
        val options = FilterOptions(
            HailData.filterUserApps, HailData.filterSystemApps,
            HailData.filterFrozenApps, HailData.filterUnfrozenApps,
            HailData.filterDisabledApps, HailData.filterHiddenApps,
            HailData.nineKeySearch, HailData.sortBy, HailData.workingMode
        )
        val revision = ++filterRevision
        filterJob?.cancel()
        filterBusy = true
        updateRefreshState()
        filterJob = viewModelScope.launch {
            try {
                val filtered = filterList(appList, queryText, inventory, options)
                ensureActive()
                if (revision == filterRevision) displayApps.value = filtered
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (revision == filterRevision) mutableError.value = HUI.operationError(error)
            } finally {
                if (revision == filterRevision) {
                    filterBusy = false
                    updateRefreshState()
                }
            }
        }
    }

    private data class FilterOptions(
        val userApps: Boolean,
        val systemApps: Boolean,
        val frozenApps: Boolean,
        val unfrozenApps: Boolean,
        val disabledApps: Boolean,
        val hiddenApps: Boolean,
        val nineKeySearch: Boolean,
        val sortBy: String?,
        val workingMode: String
    )

    private val ApplicationInfo.isSystemApp: Boolean
        get() = flags and ApplicationInfo.FLAG_SYSTEM == ApplicationInfo.FLAG_SYSTEM

    private suspend fun filterList(
        appList: List<ApplicationInfo>,
        query: String?,
        inventory: List<DiscoveredApp>,
        options: FilterOptions
    ): List<ApplicationInfo> = withContext(Dispatchers.Default) {
        val pm = getApplication<HailApp>().packageManager
        val discoveryByPackage = inventory.associateBy { it.packageName }
        appList.filter {
            ensureActive()
            ((options.userApps && !it.isSystemApp)
                    || (options.systemApps && it.isSystemApp))

                    && FreezePresentationPolicy.includes(it.observeFrozenState(options.workingMode),
                        options.frozenApps, options.unfrozenApps)
                    && (options.disabledApps || discoveryByPackage[it.packageName]?.enabled != false)
                    && (options.hiddenApps || discoveryByPackage[it.packageName]?.hidden != true)
                    && ((options.nineKeySearch
                    && NineKeySearch.search(query, it.packageName, it.loadLabel(pm).toString()))
                    || FuzzySearch.search(it.packageName, query)
                    || FuzzySearch.search(it.loadLabel(pm).toString(), query)
                    || PinyinSearch.searchPinyinAll(it.loadLabel(pm).toString(), query))
        }.run {
            when (options.sortBy) {
                HailData.SORT_INSTALL -> sortedBy {
                    ensureActive()
                    HPackages.getUnhiddenPackageInfoOrNull(it.packageName)?.firstInstallTime ?: 0
                }

                HailData.SORT_UPDATE -> sortedByDescending {
                    ensureActive()
                    HPackages.getUnhiddenPackageInfoOrNull(it.packageName)?.lastUpdateTime ?: 0
                }

                else -> sortedWith(Comparator { left, right ->
                    ensureActive()
                    NameComparator.compare(left, right)
                })
            }
        }
    }
}
