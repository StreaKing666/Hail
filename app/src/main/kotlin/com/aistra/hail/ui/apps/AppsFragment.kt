package com.aistra.hail.ui.apps

import android.os.Bundle
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.CompoundButton
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.FragmentAppsBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.main.ManualOperationsViewModel
import com.aistra.hail.ui.main.ManualResult
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.aistra.hail.views.HRecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException

class AppsFragment : MainFragment(), AppsAdapter.OnItemClickListener, AppsAdapter.OnItemCheckedChangeListener,
    MenuProvider {

    private val model: AppsViewModel by viewModels()
    private val manualOperations: ManualOperationsViewModel by viewModels()

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var appsAdapter: AppsAdapter

    // Prevent the same data from being filtered twice in `onCreateView`
    private var lastAppsHash: Int = 0
    private lateinit var lastQuery: String
    private val isAppsChanged get() = model.apps.value.hashCode() != lastAppsHash
    private val isQueryChanged get() = model.query.value != lastQuery
    private var contextMenuInfo: ContextMenu.ContextMenuInfo? = null


    private var packageReceiverRegistered = false
    private val packageChangesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            model.updateAppList()
        }
    }

    private var exportApkPkg: String? = null
    private val exportApk =
        registerForActivityResult(CreateDocument("application/vnd.android.package-archive")) { uri ->
            val pkg = exportApkPkg
            exportApkPkg = null
            if (pkg == null || uri == null) return@registerForActivityResult
            manualOperations.run { copyApk(pkg, uri) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportApkPkg = savedInstanceState?.getString("exportApkPkg")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportApkPkg", exportApkPkg)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        appsAdapter = AppsAdapter().apply {
            onItemClickListener = this@AppsFragment
            onItemCheckedChangeListener = this@AppsFragment
        }
        binding.refresh.apply {
            setOnRefreshListener { updateAppList() }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        binding.recyclerView.apply {
            activity.appbar.setLiftOnScrollTargetView(this)
            layoutManager = GridLayoutManager(activity, HailData.appListSpanCount)
            adapter = appsAdapter
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }
            registerForContextMenu(this)
        }

        model.isRefreshing.observe(viewLifecycleOwner) {
            binding.refresh.isRefreshing = it
        }
        model.apps.apply {
            lastAppsHash = value.hashCode()
            observe(viewLifecycleOwner) {
                if (isAppsChanged) updateDisplayAppList()
                lastAppsHash = it.hashCode()
            }
        }
        model.query.apply {
            lastQuery = value ?: ""
            observe(viewLifecycleOwner) {
                if (isQueryChanged) updateDisplayAppList()
                lastQuery = it
            }
        }
        model.displayApps.observe(viewLifecycleOwner) {
            appsAdapter.submitList(
                if (com.aistra.hail.app.AppDataAccess.status.value == com.aistra.hail.app.RecoveryStatus.READY)
                    it else emptyList()
            )
        }
        model.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                model.clearError()
                HUI.showToast(R.string.operation_failed, error, true)
            }
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                manualOperations.state.collect { event ->
                    event.error?.let {
                        manualOperations.consume(event.id)
                        HUI.showToast(R.string.operation_failed, it, true)
                        refreshCheckedState()
                        updateAppList()
                    }
                    event.result?.let { result ->
                        manualOperations.consume(event.id)
                        runCatching {
                            result.intent?.let(::startActivity)
                            if (result.messageRes != 0) HUI.showToast(result.messageRes, result.value ?: "")
                            if (result.refresh) {
                                refreshCheckedState()
                                updateAppList()
                            }
                        }.onFailure { HUI.showToast(R.string.operation_failed, HUI.operationError(it), true) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!packageReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(
                requireContext(),
                packageChangesReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            packageReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (packageReceiverRegistered) {
            runCatching { requireContext().unregisterReceiver(packageChangesReceiver) }
            packageReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount = HailData.appListSpanCount
        if (com.aistra.hail.app.AppDataAccess.status.value == com.aistra.hail.app.RecoveryStatus.READY) {
            updateDisplayAppList()
        }
        model.updateAppList()
    }

    override fun onItemClick(buttonView: CompoundButton) {
//        buttonView.toggle()
    }

    override fun onCreateContextMenu(
        menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        contextMenuInfo = menuInfo
        val viewHolder = ((menuInfo as HRecyclerView.RecyclerViewContextMenuInfo).viewHolder as AppsAdapter.ViewHolder)
        menu.setHeaderTitle(viewHolder.info.loadLabel(activity.packageManager))
        activity.menuInflater.inflate(R.menu.menu_apps_action, menu)
        super.onCreateContextMenu(menu, v, menuInfo)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val viewHolder =
            ((contextMenuInfo as HRecyclerView.RecyclerViewContextMenuInfo).viewHolder as AppsAdapter.ViewHolder)
        val info = viewHolder.info
        val name = info.loadLabel(app.packageManager)
        val pkg = info.packageName
        when (item.itemId) {
            R.id.action_details -> HUI.startActivity(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg)
            )

            R.id.action_export_clipboard -> {
                HUI.copyText(pkg)
                HUI.showToast(R.string.msg_text_copied, pkg)
            }

            R.id.action_extract_apk -> extractApk(pkg)
            R.id.action_uninstall -> uninstallApp(name, pkg)
            R.id.action_reinstall -> {
                val appName = name.toString()
                manualOperations.run {
                    if (AppManager.reinstallApp(pkg)) ManualResult()
                    else ManualResult(R.string.operation_failed, appName, refresh = false)
                }
            }

            else -> return super.onContextItemSelected(item)
        }
        return true
    }

    private fun extractApk(pkg: String) {
        exportApkPkg = pkg
        exportApk.launch(HPackages.getUnhiddenPackageInfoOrNull(pkg)?.exportFileName ?: pkg)
    }

    private fun uninstallApp(name: CharSequence, pkg: String) {
        when {
            pkg == app.packageName -> {
                when {
                    HPolicy.isDeviceOwnerActive -> showOwnerRemoveDialog()
                    HPolicy.isProfileOwner -> manualOperations.run { HPolicy.removeProfileOwner(); ManualResult() }
                    HPolicy.isAdminActive -> manualOperations.run { HPolicy.removeActiveAdmin(); ManualResult() }
                    else -> showUninstallDialog(name, pkg)
                }
            }

            HailData.workingMode == HailData.MODE_DEFAULT -> startActivity(uninstallIntent(pkg))
            else -> showUninstallDialog(name, pkg)
        }
    }

    private fun showOwnerRemoveDialog() = activity.ownerRemoveDialog()

    private fun showUninstallDialog(name: CharSequence, pkg: String) {
        MaterialAlertDialogBuilder(activity).setTitle(name).setMessage(R.string.msg_uninstall)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                manualOperations.run {
                    if (HPackages.isAppUninstalled(pkg)) ManualResult(R.string.app_not_installed, refresh = false)
                    else if (AppManager.uninstallApp(pkg)) ManualResult()
                    else ManualResult(intent = uninstallIntent(pkg), refresh = false)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private companion object {
        fun uninstallIntent(pkg: String) = Intent(Intent.ACTION_DELETE, Uri.parse(HPackages.packageUri(pkg)))

        fun copyApk(pkg: String, uri: Uri): ManualResult {
            val info = HPackages.getApplicationInfoOrNull(pkg)
                ?: throw IOException(app.getString(R.string.app_not_installed))
            FileInputStream(info.sourceDir).use { source ->
                val target = app.contentResolver.openOutputStream(uri, "rwt")
                    ?: throw IOException("Could not open the APK export destination")
                target.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("APK export cancelled")
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            return ManualResult(R.string.msg_extract_apk, uri.toString(), refresh = false)
        }
    }

    override fun onItemCheckedChange(
        buttonView: CompoundButton, isChecked: Boolean, packageName: String
    ) {
        val accepted = manualOperations.runData {
            if (isChecked) HailData.addCheckedApp(packageName)
            else HailData.removeCheckedApp(packageName)
            ManualResult()
        }
        if (!accepted) refreshCheckedState()
    }

    private fun refreshCheckedState() {
        appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_apps, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                model.postQuery(newText, if (newText.isEmpty()) 0L else 300L)
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean {
                model.postQuery(query, 0L)
                return true
            }
        })
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        menu.findItem(
            when (HailData.sortBy) {
                HailData.SORT_INSTALL -> R.id.sort_by_install
                HailData.SORT_UPDATE -> R.id.sort_by_update
                else -> R.id.sort_by_name
            }
        ).isChecked = true
        menu.findItem(
            if (HailData.filterSystemApps) R.id.filter_system_apps else R.id.filter_user_apps
        ).isChecked = true
        menu.findItem(R.id.filter_disabled_apps).isChecked = HailData.filterDisabledApps
        menu.findItem(R.id.filter_hidden_apps).isChecked = HailData.filterHiddenApps
        menu.findItem(R.id.filter_frozen_apps).isChecked = HailData.filterFrozenApps
        menu.findItem(R.id.filter_unfrozen_apps).isChecked = HailData.filterUnfrozenApps
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort_by_name -> changeAppsSort(HailData.SORT_NAME, item)
            R.id.sort_by_install -> changeAppsSort(HailData.SORT_INSTALL, item)
            R.id.sort_by_update -> changeAppsSort(HailData.SORT_UPDATE, item)
            R.id.filter_user_apps -> changeAppsFilter(HailData.FILTER_USER_APPS, item)
            R.id.filter_system_apps -> MaterialAlertDialogBuilder(activity).setMessage(R.string.freeze_system_app)
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    changeAppsFilter(HailData.FILTER_SYSTEM_APPS, item)
                }.setNegativeButton(android.R.string.cancel, null).show()

            R.id.filter_disabled_apps -> changeAppsFilter(HailData.FILTER_DISABLED_APPS, item)
            R.id.filter_hidden_apps -> changeAppsFilter(HailData.FILTER_HIDDEN_APPS, item)
            R.id.filter_frozen_apps -> changeAppsFilter(HailData.FILTER_FROZEN_APPS, item)
            R.id.filter_unfrozen_apps -> changeAppsFilter(HailData.FILTER_UNFROZEN_APPS, item)
        }
        return false
    }

    private fun changeAppsSort(sort: String, item: MenuItem) {
        item.isChecked = true
        HailData.changeAppsSort(sort)
        updateDisplayAppList()
    }

    private fun changeAppsFilter(filter: String, item: MenuItem) {
        when (item.itemId) {
            R.id.filter_user_apps -> {
                item.isChecked = true
                HailData.changeAppsFilter(filter, item.isChecked)
                HailData.changeAppsFilter(HailData.FILTER_SYSTEM_APPS, false)
            }

            R.id.filter_system_apps -> {
                item.isChecked = true
                HailData.changeAppsFilter(filter, item.isChecked)
                HailData.changeAppsFilter(HailData.FILTER_USER_APPS, false)
            }

            else -> {
                item.isChecked = !item.isChecked
                HailData.changeAppsFilter(filter, item.isChecked)
            }
        }
        updateDisplayAppList()
    }

    private fun updateAppList() = model.updateAppList()
    private fun updateDisplayAppList() {
        if (com.aistra.hail.app.AppDataAccess.status.value == com.aistra.hail.app.RecoveryStatus.READY)
            model.updateDisplayAppList()
    }

    override fun onDestroy() {
        appsAdapter.onDestroy()
        super.onDestroy()
        _binding = null
    }
}