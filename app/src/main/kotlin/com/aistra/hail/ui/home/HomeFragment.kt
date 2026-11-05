package com.aistra.hail.ui.home

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.fragment.app.viewModels
import com.aistra.hail.ui.settings.EnhancedOperationsViewModel
import com.aistra.hail.ui.settings.EnhancedResult
import com.aistra.hail.ui.settings.OperationProgressDialog
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.BuildConfig
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.FullBackupArchive
import com.aistra.hail.app.FullBackupPayload
import com.aistra.hail.app.FullBackupRestoreMode
import com.aistra.hail.app.FullBackupRestorePlan
import com.aistra.hail.app.FullBackupSelection
import com.aistra.hail.app.FullBackupStorage
import com.aistra.hail.app.HailData
import com.aistra.hail.app.TagBackupArchive
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HShortcuts
import com.aistra.hail.app.HailData.tags
import com.aistra.hail.databinding.FragmentHomeBinding
import com.aistra.hail.extensions.applyDefaultInsetter
import com.aistra.hail.extensions.isLandscape
import com.aistra.hail.extensions.isRtl
import com.aistra.hail.extensions.paddingRelative
import com.aistra.hail.ui.main.MainFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class HomeFragment : MainFragment() {
    private val restoreOperations: EnhancedOperationsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                restoreOperations.state.collect { event ->
                    OperationProgressDialog.update(childFragmentManager, event.busy)
                    if (event.error != null) {
                        Toast.makeText(app, getString(R.string.tag_backup_error, event.error), Toast.LENGTH_LONG).show()
                        restoreOperations.consume(event.id)
                    }
                    if (event.result == EnhancedResult.Restored) {
                        restoreOperations.consume(event.id)
                        refreshTags(0)
                        runCatching { app.setAutoFreezeService(false) }
                        HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
                        Toast.makeText(app, R.string.full_backup_import_success, Toast.LENGTH_LONG).show()
                        app.setAppTheme(HailData.appTheme)
                    }
                }
            }
        }
    }

    var multiselect: Boolean = false
    val selectedList: MutableList<AppInfo> = mutableListOf()
    private var _binding: FragmentHomeBinding? = null
    val binding get() = _binding!!

    private val exportTagBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let(::writeTagBackup)
        }

    private val importTagBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::readTagBackup)
        }

    private val exportFullBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let(::writeFullBackup)
        }

    private var fullBackupSelection = FullBackupSelection()

    private val importFullBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::readFullBackup)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        binding.pager.adapter = HomeAdapter(this)
        TabLayoutMediator(binding.tabs, binding.pager) { tab, position ->
            tab.text = (binding.pager.adapter as HomeAdapter).tagAt(position)?.first ?: getString(R.string.label_default)
        }.attach()
        binding.tabs.post { bindTagLongPress() }
        binding.tabs.applyDefaultInsetter { paddingRelative(isRtl, start = !activity.isLandscape, end = true) }
        return binding.root
    }

    fun selectedTagId(): Int = tags.getOrNull(binding.pager.currentItem)?.second ?: 0

    fun refreshTags(preferredTagId: Int?) {
        val pagerAdapter = binding.pager.adapter as? HomeAdapter ?: return
        pagerAdapter.refreshTags()
        pagerAdapter.notifyDataSetChanged()
        // Keep even the Default tab visible: long-press is the single tag-editor entry point.
        binding.tabs.isVisible = true
        binding.tabs.post {
            bindTagLongPress()
            val position = tags.indexOfFirst { it.second == preferredTagId }
            binding.pager.setCurrentItem(position.takeIf { it >= 0 } ?: 0, false)
            childFragmentManager.fragments
                .filterIsInstance<PagerFragment>()
                .firstOrNull { it.isResumed }
                ?.refreshAfterTagConfigChanged()
        }
    }

    fun refreshAppLists() {
        childFragmentManager.fragments
            .filterIsInstance<PagerFragment>()
            .forEach { it.refreshAfterTagConfigChanged() }
    }

    fun launchTagBackupExport() {
        exportTagBackupLauncher.launch("Hail-Tag-Backup.zip")
    }

    fun launchTagBackupImport() {
        importTagBackupLauncher.launch(
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
        )
    }

    fun launchFullBackupExport() {
        val selected = booleanArrayOf(true, true)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.full_backup_export)
            .setMultiChoiceItems(
                arrayOf(
                    getString(R.string.full_backup_additional_files),
                    getString(R.string.full_backup_preferences)
                ),
                selected
            ) { _, which, checked -> selected[which] = checked }
            .setPositiveButton(R.string.action_continue) { _, _ ->
                fullBackupSelection = FullBackupSelection(
                    includeAdditionalFiles = selected[0],
                    includePreferences = selected[1]
                )
                exportFullBackupLauncher.launch("Hail-Enhanced-Full-Backup.zip")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun launchFullBackupImport() {
        importFullBackupLauncher.launch(
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
        )
    }

    private fun writeFullBackup(uri: Uri) {
        val resolver = requireContext().contentResolver
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { FullBackupStorage.createArchive(fullBackupSelection) }
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the selected destination")
                    output.use { it.write(bytes) }
                }
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.full_backup_export_success, Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.tag_backup_error, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun readFullBackup(uri: Uri) {
        val resolver = requireContext().contentResolver
        lifecycleScope.launch {
            val payload = try {
                withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("Could not open the selected backup")
                    input.use(FullBackupArchive::decode)
                }
            } catch (error: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.tag_backup_error, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            if (!isAdded) return@launch
            val selectedCategories = booleanArrayOf(
                payload.selection.includeAdditionalFiles, payload.selection.includePreferences
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.full_backup_import_title)
                .setMultiChoiceItems(
                    arrayOf(
                        getString(R.string.full_backup_additional_files),
                        getString(R.string.full_backup_preferences)
                    ),
                    selectedCategories
                ) { _, which, checked -> selectedCategories[which] = checked }
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    val selectedPayload = FullBackupRestorePlan.select(
                        payload,
                        FullBackupSelection(
                            includeAdditionalFiles = selectedCategories[0],
                            includePreferences = selectedCategories[1]
                        )
                    )
                    confirmFullBackupRestore(selectedPayload)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun confirmFullBackupRestore(payload: FullBackupPayload) {
        var selectedRestoreMode = FullBackupRestoreMode.MERGE
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.full_backup_import_title)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.full_backup_mode_merge),
                    getString(R.string.full_backup_mode_replace)
                ),
                0
            ) { _, which ->
                selectedRestoreMode = if (which == 0) {
                    FullBackupRestoreMode.MERGE
                } else {
                    FullBackupRestoreMode.REPLACE
                }
            }
            .setPositiveButton(R.string.action_continue) { _, _ ->
                showFullBackupRestorePreview(payload, selectedRestoreMode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFullBackupRestorePreview(payload: FullBackupPayload, mode: FullBackupRestoreMode) {
        val modeLabel = getString(
            if (mode == FullBackupRestoreMode.MERGE) R.string.full_backup_mode_merge
            else R.string.full_backup_mode_replace
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.full_backup_import_title)
            .setMessage(modeLabel + "\n\n" + getString(
                R.string.full_backup_import_summary,
                payload.tagBackup.tags.size,
                payload.tagBackup.apps.size,
                payload.files.size,
                payload.preferences.size,
                payload.schemaVersion
            ))
            .setPositiveButton(R.string.full_backup_import_confirm) { _, _ ->
                restoreOperations.run {
                    FullBackupStorage.restore(payload, mode)
                    EnhancedResult.Restored
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeTagBackup(uri: Uri) {
        val resolver = requireContext().contentResolver
        val applicationId = BuildConfig.APPLICATION_ID
        val versionName = BuildConfig.VERSION_NAME

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    AppDataAccess.access { TagBackupArchive.encode(HailData.createTagBackup(), applicationId, versionName) }
                }
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the selected destination")
                    output.use { it.write(bytes) }
                }
                if (isAdded) {
                    Toast.makeText(
                        requireContext(), R.string.tag_backup_export_success, Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.tag_backup_error, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun readTagBackup(uri: Uri) {
        val resolver = requireContext().contentResolver
        lifecycleScope.launch {
            val backup = try {
                withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("Could not open the selected backup")
                    input.use(TagBackupArchive::decode)
                }
            } catch (error: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.tag_backup_error, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.tag_backup_import_title)
                .setMessage(
                    getString(
                        R.string.tag_backup_import_summary,
                        backup.tags.size,
                        backup.apps.size
                    )
                )
                .setPositiveButton(R.string.tag_backup_import_confirm) { _, _ ->
                    val selectedId = selectedTagId()
                    try {
                        HailData.importTagBackup(backup)
                        refreshTags(selectedId)
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.tag_backup_import_success,
                                backup.tags.size,
                                backup.apps.size
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (error: Exception) {
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.tag_backup_error,
                                error.message ?: error.javaClass.simpleName
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun bindTagLongPress() {
        for (position in 0 until binding.tabs.tabCount) {
            binding.tabs.getTabAt(position)?.view?.setOnLongClickListener {
                if (isResumed) AppDataAccess.tryAccess { TagEditorDialog(this).show() }.onFailure {
                    Toast.makeText(app, getString(R.string.operation_failed, com.aistra.hail.utils.HUI.operationError(it)), Toast.LENGTH_LONG).show()
                }
                true
            }
        }
    }

    override fun onDestroyView() {
        multiselect = false
        selectedList.clear()
        super.onDestroyView()
        _binding = null
    }
}
