package com.aistra.hail.ui.home

import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.viewModels
import com.aistra.hail.ui.main.ManualOperationsViewModel
import com.aistra.hail.ui.main.ManualResult
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailApi.addTag
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.aistra.hail.work.HWork
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import org.json.JSONArray

class PagerFragment : MainFragment(), PagerAdapter.OnItemClickListener, PagerAdapter.OnItemLongClickListener,
    MenuProvider {
    private var query: String = String()
    private val manualOperations: ManualOperationsViewModel by viewModels()
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private var multiselect: Boolean
        set(value) {
            (parentFragment as HomeFragment).multiselect = value
        }
        get() = (parentFragment as HomeFragment).multiselect
    private val selectedList get() = (parentFragment as HomeFragment).selectedList
    private val tabs: TabLayout? get() = (parentFragment as? HomeFragment)?.binding?.tabs
    private val adapter: HomeAdapter? get() = (parentFragment as? HomeFragment)?.binding?.pager?.adapter as? HomeAdapter
    private val tag: Pair<String, Int>? get() = adapter?.tagAt(tabs?.selectedTabPosition ?: -1)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(selectedList).apply {
            onItemClickListener = this@PagerFragment
            onItemLongClickListener = this@PagerFragment
        }
        binding.recyclerView.run {
            layoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            )
            adapter = pagerAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> activity.fab.run {
                            postDelayed({ if (tag != null) show() }, 1000)
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> activity.fab.hide()
                    }
                }
            })
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }

        }

        binding.refresh.apply {
            setOnRefreshListener {
                updateCurrentList()
                binding.refresh.isRefreshing = false
            }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        updateCurrentList()
        updateBarTitle()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        activity.fab.setOnClickListener {
            setListFrozen(true, pagerAdapter.currentList.filterNot { it.whitelisted })
        }
        activity.fab.setOnLongClickListener {
            setListFrozen(true)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                manualOperations.state.collect { event ->
                    binding.refresh.isRefreshing = event.busy
                    activity.fab.isEnabled = !event.busy
                    event.error?.let {
                        manualOperations.consume(event.id)
                        HUI.showToast(R.string.operation_failed, it, true)
                    }
                    event.result?.let { result ->
                        manualOperations.consume(event.id)
                        if (result.tagsChanged) {
                            val selectedTagId = adapter?.tagAt(tabs?.selectedTabPosition ?: -1)?.second
                            (parentFragment as? HomeFragment)?.refreshTags(selectedTagId)
                        }
                        if (result.refresh) updateCurrentList()
                        result.tagEditorPackages?.let { requested ->
                            val apps = HailData.checkedList.filter { (it.packageName to it.userId) in requested }
                            if (apps.isNotEmpty()) {
                                if (result.tagEditorMultiselect) {
                                    multiselect = true
                                    selectedList.clear()
                                    selectedList.addAll(apps)
                                    updateBarTitle()
                                    triStateTagDialog(apps)
                                } else tagDialog(apps.first())
                            }
                        }
                        if (result.messageRes != 0) {
                            if (result.value != null) HUI.showToast(result.messageRes, result.value)
                            else HUI.showToast(result.messageRes)
                        }
                        result.intent?.let { intent ->
                            runCatching { startActivity(intent) }.onFailure {
                                HUI.showToast(R.string.operation_failed, HUI.operationError(it), true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mutateData(block: () -> Unit) {
        AppDataAccess.tryAccess(block).onFailure {
            HUI.showToast(R.string.operation_failed, HUI.operationError(it), true)
        }
    }

    private fun updateCurrentList() {
        if (AppDataAccess.status.value != com.aistra.hail.app.RecoveryStatus.READY) return
        HailData.checkedList.filter {
        if (query.isEmpty()) tag?.second?.let { tagId -> tagId in it.tagIdList } ?: false
        else ((HailData.nineKeySearch && NineKeySearch.search(
            query, it.packageName, it.name.toString()
        )) || FuzzySearch.search(it.packageName, query) || FuzzySearch.search(
            it.name.toString(), query
        ) || PinyinSearch.searchPinyinAll(it.name.toString(), query))
    }.sortedWith(NameComparator).let {
        binding.empty.isVisible = it.isEmpty()
        pagerAdapter.submitList(it)
        app.setAutoFreezeService()
        }
    }

    private fun updateBarTitle() {
        activity.supportActionBar?.title =
            if (multiselect) getString(R.string.msg_selected, selectedList.size.toString())
            else getString(R.string.app_name)
    }

    override fun onItemClick(info: AppInfo) {
        if (multiselect) {
            if (info in selectedList) selectedList.remove(info)
            else selectedList.add(info)
            updateCurrentList()
            updateBarTitle()
            return
        }
        if (info.applicationInfo == null) {
            Snackbar.make(activity.fab, R.string.app_not_installed, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_remove_home) { removeCheckedApps(listOf(info.packageName to info.userId)) }.show()
            return
        }
        launchApp(info.packageName)
    }

    override fun onItemLongClick(info: AppInfo): Boolean {
        if (info.applicationInfo == null && (!multiselect || info !in selectedList)) {
            exportToClipboard(listOf(info))
            return true
        }
        if (info in selectedList) {
            onMultiSelect()
            return true
        }
        val pkg = info.packageName
        val frozen = AppManager.isAppFrozen(pkg)
        val action = getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)
        MaterialAlertDialogBuilder(activity).setTitle(info.name).setItems(
            resources.getStringArray(R.array.home_action_entries).filter {
                (it != getString(R.string.action_freeze) || !frozen) && (it != getString(R.string.action_unfreeze) || frozen) && (it != getString(
                    R.string.action_pin
                ) || !info.pinned) && (it != getString(R.string.action_unpin) || info.pinned) && (it != getString(
                    R.string.action_whitelist
                ) || !info.whitelisted) && (it != getString(R.string.action_remove_whitelist) || info.whitelisted) && (it != getString(
                    R.string.action_unfreeze_remove_home
                ) || frozen)
            }.toTypedArray()
        ) { _, which ->
            mutateData {
            when (which) {
                0 -> launchApp(pkg)
                1 -> setListFrozen(!frozen, listOf(info))
                2 -> {
                    val values = resources.getIntArray(R.array.deferred_task_values)
                    val entries = arrayOfNulls<String>(values.size)
                    values.forEachIndexed { i, it ->
                        entries[i] = resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
                    }
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.action_deferred_task)
                        .setItems(entries) { _, i ->
                            HWork.setDeferredFrozen(pkg, !frozen, values[i].toLong())
                            Snackbar.make(
                                activity.fab, resources.getQuantityString(
                                    R.plurals.msg_deferred_task, values[i], values[i], action, info.name
                                ), Snackbar.LENGTH_INDEFINITE
                            ).setAction(R.string.action_undo) { HWork.cancelWork(pkg) }.show()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }

                3 -> setAppFlag(info, pinned = !info.pinned)

                4 -> setAppFlag(info, whitelisted = !info.whitelisted)

                5 -> tagDialog(info)

                6 -> tabs?.takeIf { it.tabCount > 1 }?.let {
                    val shortcutTags = HailData.tags.toList()
                    MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                        .setItems(shortcutTags.map { it.first }.toTypedArray()) { _, index ->
                            val shortcutTagId = shortcutTags[index].second
                            val shortcutTagName = HailData.tags.firstOrNull { it.second == shortcutTagId }?.first
                                ?: return@setItems
                            HShortcuts.addPinShortcut(
                                info,
                                pkg,
                                info.name,
                                HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg).addTag(shortcutTagName)
                            )
                        }.setPositiveButton(R.string.action_skip) { _, _ ->
                            HShortcuts.addPinShortcut(
                                info, pkg, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                            )
                        }.setNegativeButton(android.R.string.cancel, null).show()
                } ?: HShortcuts.addPinShortcut(
                    info, pkg, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                )

                7 -> exportToClipboard(listOf(info))
                8 -> removeCheckedApps(listOf(pkg to info.userId))
                9 -> {
                    setListFrozen(false, listOf(info), removeAfter = true)
                }
            }

            }
        }.setNeutralButton(R.string.action_details) { _, _ ->
            HUI.startActivity(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg)
            )
        }.setNegativeButton(android.R.string.cancel, null).show()
        return true
    }

    private fun setAppFlag(info: AppInfo, pinned: Boolean? = null, whitelisted: Boolean? = null) {
        val requested = info.packageName to info.userId
        manualOperations.runData {
            HailData.checkedList.firstOrNull { (it.packageName to it.userId) == requested }?.let { current ->
                pinned?.let { current.pinned = it }
                whitelisted?.let { current.whitelisted = it }
                HailData.saveApps()
            }
            ManualResult()
        }
    }

    private fun tagDialog(info: AppInfo) {
        val requested = info.packageName to info.userId
        val displayedTags = HailData.tags.toList()
        val current = HailData.checkedList.firstOrNull { (it.packageName to it.userId) == requested } ?: return
        val checkedItems = BooleanArray(displayedTags.size) { index ->
            displayedTags[index].second in current.tagIdList
        }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setMultiChoiceItems(
            displayedTags.map { it.first }.toTypedArray(), checkedItems
        ) { _, index, isChecked ->
            checkedItems[index] = isChecked
        }.setPositiveButton(android.R.string.ok) { _, _ ->
            val assignments = displayedTags.mapIndexed { index, tag -> tag.second to checkedItems[index] }
            manualOperations.runData {
                val validTagIds = HailData.tags.map { it.second }.toSet()
                HailData.checkedList.firstOrNull { (it.packageName to it.userId) == requested }?.let { appInfo ->
                    assignments.forEach { (tagId, checked) ->
                        if (tagId in validTagIds) {
                            if (checked && tagId !in appInfo.tagIdList) appInfo.tagIdList.add(tagId)
                            else if (!checked) appInfo.tagIdList.removeAll { it == tagId }
                        }
                    }
                    if (appInfo.tagIdList.isEmpty()) HailData.checkedList.remove(appInfo)
                    HailData.saveApps()
                }
                ManualResult()
            }
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(listOf(current))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun deselect(update: Boolean = true) {
        selectedList.clear()
        if (!update) return
        updateCurrentList()
        updateBarTitle()
    }

    private fun onMultiSelect() {
        MaterialAlertDialogBuilder(activity).setTitle(
            getString(
                R.string.msg_selected, selectedList.size.toString()
            )
        ).setItems(
            intArrayOf(
                R.string.action_freeze,
                R.string.action_unfreeze,
                R.string.action_tag_set,
                R.string.action_export_clipboard,
                R.string.action_remove_home,
                R.string.action_unfreeze_remove_home
            ).map { getString(it) }.toTypedArray()
        ) { _, which ->
            mutateData {
            when (which) {
                0 -> {
                    setListFrozen(true, selectedList, false)
                    deselect()
                }

                1 -> {
                    setListFrozen(false, selectedList, false)
                    deselect()
                }

                2 -> triStateTagDialog()

                3 -> {
                    exportToClipboard(selectedList)
                    deselect()
                }

                4 -> {
                    val requested = selectedList.map { it.packageName to it.userId }
                    if (removeCheckedApps(requested)) deselect()
                }

                5 -> {
                    setListFrozen(false, selectedList.toList(), removeAfter = true)
                    deselect()
                }
            }

            }
        }.setNegativeButton(R.string.action_deselect) { _, _ ->
            deselect()
        }.setNeutralButton(R.string.action_select_all) { _, _ ->
            selectedList.addAll(pagerAdapter.currentList.filterNot { it in selectedList })
            updateCurrentList()
            updateBarTitle()
            onMultiSelect()
        }.show()
    }

    private fun triStateTagDialog(list: List<AppInfo> = selectedList.toList()) {
        val requested = list.map { it.packageName to it.userId }.toSet()
        val displayedTags = HailData.tags.toList()
        val current = HailData.checkedList.filter { (it.packageName to it.userId) in requested }
        if (current.isEmpty()) return
        val initialStates = Array(displayedTags.size) { index ->
            val tagId = displayedTags[index].second
            when (current.count { tagId in it.tagIdList }) {
                current.size -> ToggleableState.On
                0 -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }
        }
        val states = mutableStateListOf(*initialStates)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setView(ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AppTheme { TriStateTagList(displayedTags, initialStates, states) } }
        }).setPositiveButton(android.R.string.ok) { _, _ ->
            val assignments = displayedTags.mapIndexed { index, tag -> tag.second to states[index] }
            val accepted = manualOperations.runData {
                val validTagIds = HailData.tags.map { it.second }.toSet()
                HailData.checkedList.filter { (it.packageName to it.userId) in requested }.forEach { appInfo ->
                    assignments.forEach { (tagId, state) ->
                        if (tagId in validTagIds) {
                            when (state) {
                                ToggleableState.On -> {
                                    if (tagId !in appInfo.tagIdList) appInfo.tagIdList.add(tagId)
                                }
                                ToggleableState.Off -> appInfo.tagIdList.removeAll { it == tagId }
                                ToggleableState.Indeterminate -> Unit
                            }
                        }
                    }
                    if (appInfo.tagIdList.isEmpty()) HailData.checkedList.remove(appInfo)
                }
                HailData.saveApps()
                ManualResult()
            }
            if (accepted) deselect()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(current, multiple = true)
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Composable
    private fun TriStateTagList(
        displayedTags: List<Pair<String, Int>>, initialStates: Array<ToggleableState>, states: MutableList<ToggleableState>
    ) = Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        displayedTags.forEachIndexed { index, tag ->
            Row(modifier = Modifier.fillMaxWidth().clickable {
                states[index] = if (initialStates[index] == ToggleableState.Indeterminate) when (states[index]) {
                    ToggleableState.On -> ToggleableState.Off
                    ToggleableState.Off -> ToggleableState.Indeterminate
                    ToggleableState.Indeterminate -> ToggleableState.On
                }
                else if (states[index] == ToggleableState.On) ToggleableState.Off
                else ToggleableState.On
            }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = states[index],
                    onClick = null,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary)
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = tag.first,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    private fun launchApp(packageName: String) {
        manualOperations.run {
            val state = HPackages.getFreezeState(packageName)
                ?: throw IllegalStateException(app.getString(R.string.app_not_installed))
            if (state.disabled || state.hidden == true || state.suspended) {
                check(AppManager.setAppFrozen(packageName, false)) { app.getString(R.string.state_unverified) }
            }
            if (HailData.workingMode == HailData.MODE_ISLAND_HIDE) HIsland.ensureLaunchIntentExists(packageName)
            val intent = app.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalStateException(app.getString(R.string.activity_not_found))
            HShortcuts.addDynamicShortcut(packageName)
            app.setAutoFreezeService()
            ManualResult(intent = intent)
        }
    }

    private fun setListFrozen(
        frozen: Boolean, list: List<AppInfo> = HailData.checkedList, updateList: Boolean = true,
        removeAfter: Boolean = false
    ) {
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        } else if (HailData.workingMode == HailData.MODE_SHIZUKU_HIDE) {
            runCatching { HShizuku.isRoot }.onSuccess {
                if (!it) {
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.shizuku_hide_adb)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return
                }
            }
        }
        val requested = list.map { it.packageName to it.userId }.toSet()
        manualOperations.run {
            // Reconcile against current data after any queued restore/edit has completed.
            val current = HailData.checkedList.filter { (it.packageName to it.userId) in requested &&
                it.userId == HPackages.myUserId }
            val filtered = current.filter { AppManager.observeAppFrozen(it.packageName) != frozen }
            val result = AppManager.setListFrozen(frozen, *filtered.toTypedArray())
            if (removeAfter && !frozen) com.aistra.hail.app.FullBackupStorage.editTagState {
                current.forEach { info ->
                    val state = HPackages.getFreezeState(info.packageName)
                    if (state != null && com.aistra.hail.app.FreezeModePolicy.isFrozen(HailData.workingMode,
                            state.disabled, state.hidden, state.suspended, state.stopped) == false) {
                        HailData.checkedList.removeAll {
                            it.packageName == info.packageName && it.userId == info.userId
                        }
                    }
                }
                HailData.saveApps()
            }
            app.setAutoFreezeService()
            ManualResult(if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, result,
                refresh = updateList || removeAfter)
        }
    }

    fun refreshAfterTagConfigChanged() {
        if (_binding != null) updateCurrentList()
    }

    private fun showTagDialog(list: List<AppInfo>? = null, multiple: Boolean = false) {
        val requested = list?.map { it.packageName to it.userId }?.toList()
        val originalTag = tag
        if (requested == null && originalTag == null) return
        val targetTagId = if (requested == null) originalTag?.second else null
        val dialogBinding = DialogInputBinding.inflate(layoutInflater)
        dialogBinding.inputLayout.setHint(R.string.tag)
        if (requested == null) dialogBinding.editText.setText(originalTag?.first.orEmpty())
        MaterialAlertDialogBuilder(activity).setTitle(if (requested != null) R.string.action_tag_add else R.string.action_tag_set)
            .setView(dialogBinding.root).setPositiveButton(android.R.string.ok) { _, _ ->
                // Only immutable values enter the retained worker, never the dialog or pager views.
                val tagName = dialogBinding.editText.text.toString().trim()
                manualOperations.run {
                    require(tagName.isNotEmpty()) { app.getString(R.string.tag_name_required) }
                    require(HailData.tags.none { it.second != targetTagId && it.first.equals(tagName, true) }) {
                        app.getString(R.string.tag_duplicate_name)
                    }
                    val position = if (requested == null) HailData.tags.indexOfFirst { it.second == targetTagId } else -1
                    if (requested == null && position < 0) return@run ManualResult()
                    val oldName = if (position >= 0) HailData.tags[position].first else null
                    val newId = if (requested != null) TagEditSession(HailData.tags.toList(), emptyList()).addTag(tagName) else null
                    if (requested != null) require(newId != null) { app.getString(R.string.tag_duplicate_id) }
                    com.aistra.hail.app.FullBackupStorage.editTagState {
                        if (requested != null) HailData.tags.add(tagName to checkNotNull(newId))
                        else HailData.tags[position] = tagName to HailData.tags[position].second
                        HailData.saveTags()
                    }
                    val shortcutsUpdated = oldName?.let { HShortcuts.refreshTagShortcuts(it, tagName) } ?: true
                    ManualResult(
                        messageRes = if (shortcutsUpdated) 0 else R.string.operation_failed,
                        value = if (shortcutsUpdated) null else app.getString(R.string.action_add_pin_shortcut),
                        tagsChanged = true, tagEditorPackages = requested, tagEditorMultiselect = multiple)
                }
            }.apply {
                if (requested != null || targetTagId == 0) return@apply
                setNeutralButton(R.string.action_tag_remove) { _, _ ->
                    manualOperations.run {
                        val currentTag = HailData.tags.firstOrNull { it.second == targetTagId }
                        if (currentTag != null) {
                            com.aistra.hail.app.FullBackupStorage.editTagState {
                                HailData.checkedList.toList().forEach { appInfo ->
                                    if (appInfo.tagIdList.removeAll { it == targetTagId } && appInfo.tagIdList.isEmpty()) {
                                        HailData.checkedList.remove(appInfo)
                                    }
                                }
                                HailData.tags.removeAll { it.second == targetTagId }
                                HailData.saveApps()
                                HailData.saveTags()
                            }
                            HShortcuts.removeTagShortcuts(currentTag.first)
                        }
                        ManualResult(tagsChanged = true)
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun exportToClipboard(list: List<AppInfo>) {
        if (list.isEmpty()) return
        HUI.copyText(if (list.size > 1) JSONArray().run {
            list.forEach { put(it.packageName) }
            toString()
        } else list[0].packageName)
        HUI.showToast(
            R.string.msg_exported, if (list.size > 1) list.size.toString() else list[0].name
        )
    }

    private fun importFromClipboard() {
        // Android clipboard access belongs to the foreground UI; parsing and persistence do not.
        val text = HUI.pasteText().orEmpty()
        val requestedTagId = tag?.second ?: 0
        manualOperations.runData {
            require(text.isNotBlank()) { app.getString(R.string.nothing_here) }
            val json = if (text.contains('[')) JSONArray(
                text.substring(text.indexOf('[')..text.indexOf(']', text.indexOf('[')))
            ) else JSONArray().put(text.trim())
            val tagId = requestedTagId.takeIf { id -> HailData.tags.any { it.second == id } } ?: 0
            // Parse and validate the complete request before changing the tracked list.
            val packages = List(json.length()) { json.getString(it).trim() }.distinct().filter { packageName ->
                HPackages.getApplicationInfoOrNull(packageName) != null &&
                    HailData.checkedList.none { it.packageName == packageName && it.userId == HPackages.myUserId }
            }
            packages.forEach { HailData.addCheckedApp(it, tagId, false) }
            if (packages.isNotEmpty()) HailData.saveApps()
            ManualResult(R.string.msg_imported, packages.size.toString())
        }
    }

    private fun importFrozenApp() {
        val requestedTagId = tag?.second ?: 0
        manualOperations.runData {
            val tagId = requestedTagId.takeIf { id -> HailData.tags.any { it.second == id } } ?: 0
            val packages = HPackages.getInstalledApplications().map { it.packageName }
                .filter { AppManager.isAppFrozen(it) && !HailData.isChecked(it) }
            packages.forEach { HailData.addCheckedApp(it, tagId, false) }
            if (packages.isNotEmpty()) HailData.saveApps()
            ManualResult(R.string.msg_imported, packages.size.toString())
        }
    }

    private fun removeCheckedApps(packages: List<Pair<String, Int>>): Boolean {
        val requested = packages.toSet()
        return manualOperations.runData {
            if (HailData.checkedList.removeAll { (it.packageName to it.userId) in requested }) {
                HailData.saveApps()
            }
            ManualResult()
        }
    }

    private fun MenuItem.updateIcon() = icon?.setTint(
        MaterialColors.getColor(
            activity.findViewById(R.id.toolbar),
            if (multiselect) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
        )
    )

    override fun onMenuItemSelected(item: MenuItem): Boolean = AppDataAccess.tryAccess {
        when (item.itemId) {
            R.id.action_export_full_backup -> {
                (parentFragment as? HomeFragment)?.launchFullBackupExport()
                return@tryAccess true
            }
            R.id.action_import_full_backup -> {
                (parentFragment as? HomeFragment)?.launchFullBackupImport()
                return@tryAccess true
            }
            R.id.action_export_tag_backup -> {
                (parentFragment as? HomeFragment)?.launchTagBackupExport()
                return@tryAccess true
            }
            R.id.action_import_tag_backup -> {
                (parentFragment as? HomeFragment)?.launchTagBackupImport()
                return@tryAccess true
            }
            R.id.action_multiselect -> {
                multiselect = !multiselect
                item.updateIcon()
                if (multiselect) {
                    updateBarTitle()
                    HUI.showToast(R.string.tap_to_select)
                } else deselect()
            }

            R.id.action_freeze_current -> setListFrozen(true, pagerAdapter.currentList.filterNot { it.whitelisted })

            R.id.action_unfreeze_current -> setListFrozen(false, pagerAdapter.currentList)
            R.id.action_freeze_all -> setListFrozen(true)
            R.id.action_unfreeze_all -> setListFrozen(false)
            R.id.action_freeze_non_whitelisted -> setListFrozen(true, HailData.checkedList.filterNot { it.whitelisted })

            R.id.action_import_clipboard -> importFromClipboard()
            R.id.action_import_frozen -> importFrozenApp()

            R.id.action_export_current -> exportToClipboard(pagerAdapter.currentList)
            R.id.action_export_all -> exportToClipboard(HailData.checkedList)
        }
        false
    } .getOrElse {
        HUI.showToast(R.string.operation_failed, HUI.operationError(it), true)
        false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_home, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var inited = false
            override fun onQueryTextChange(newText: String): Boolean {
                if (inited) {
                    query = newText
                    tabs?.run {
                        isVisible = query.isEmpty()
                    }
                    updateCurrentList()
                } else inited = true
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean = true
        })
        menu.findItem(R.id.action_multiselect).updateIcon()
    }

    override fun onDestroyView() {
        activity.fab.setOnClickListener(null)
        activity.fab.setOnLongClickListener(null)
        activity.fab.isEnabled = true
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
