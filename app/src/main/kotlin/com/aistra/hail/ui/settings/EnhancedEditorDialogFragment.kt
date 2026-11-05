package com.aistra.hail.ui.settings

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.*
import com.aistra.hail.enhanced.domain.*
import com.aistra.hail.utils.HShortcuts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.*
import java.text.Collator

class EnhancedEditorDialogFragment : DialogFragment() {
    private val operations: EnhancedOperationsViewModel by viewModels()
    private lateinit var draft: EnhancedEditorDraft
    private lateinit var errorView: TextView
    private val items = mutableListOf<AppRow>()
    private val rows = AppRows()
    private val iconJobs = mutableMapOf<ImageView, Job>()
    private data class AppRow(val pkg: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = EnhancedEditorDraft.decode(savedInstanceState?.getString("draft")
            ?: requireArguments().getString("draft")!!)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                operations.state.collect { event ->
                    OperationProgressDialog.update(childFragmentManager, event.busy)
                    isCancelable = !event.busy
                    (dialog as? androidx.appcompat.app.AlertDialog)?.getButton(-1)?.isEnabled = !event.busy
                    if (event.error != null) {
                        errorView.text = event.error
                        operations.consume(event.id)
                    }
                    if (event.result is EnhancedResult.Saved) {
                        event.result.profile?.let { runCatching { HShortcuts.refreshProfileShortcuts(it) } }
                        parentFragmentManager.setFragmentResult(RESULT, Bundle.EMPTY)
                        operations.consume(event.id)
                        dismiss()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        iconJobs.values.forEach { it.cancel() }
        iconJobs.clear()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", draft.encode())
        super.onSaveInstanceState(outState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val name = EditText(context).apply {
            hint = getString(if (draft.profile) R.string.profile_name else R.string.editor_rule_name)
            setSingleLine(); setText(draft.name)
            doAfterTextChanged { draft = draft.copy(name = it.toString()) }
        }
        root.addView(name)
        if (!draft.profile) {
            root.addView(MaterialSwitch(context).apply {
                setText(R.string.editor_enabled); isChecked = draft.enabled
                setOnCheckedChangeListener { _, checked -> draft = draft.copy(enabled = checked) }
            })
            val triggerNames = listOf(R.string.rule_trigger_screen_off, R.string.rule_trigger_charging,
                R.string.rule_trigger_not_charging).map(::getString)
            root.addView(spinner(triggerNames, draft.trigger.ordinal) { draft = draft.copy(trigger = RuleTrigger.entries[it]) })
            root.addView(spinner(listOf(getString(R.string.rule_action_freeze), getString(R.string.rule_action_unfreeze)),
                draft.action.ordinal) { draft = draft.copy(action = RuleAction.entries[it]) })
            root.addView(TextView(context).apply { setText(R.string.enhanced_cooldown_seconds) })
            root.addView(EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(); setText(draft.cooldown)
                doAfterTextChanged { draft = draft.copy(cooldown = it.toString()) }
            })
        }
        root.addView(TextView(context).apply { setText(R.string.editor_apps_hint) })
        root.addView(EditText(context).apply {
            setSingleLine(); hint = getString(R.string.editor_search); setText(draft.search)
            doAfterTextChanged { draft = draft.copy(search = it.toString()); rows.refresh() }
        })
        root.addView(RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = rows
        }, LinearLayout.LayoutParams(-1, dp(280)))
        errorView = TextView(context).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(errorView)
        val scroll = ScrollView(context).apply { addView(root) }
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val packages = AppDataAccess.access {
                        (draft.states.keys + HailData.checkedList.map { it.packageName }).filter { it != app.packageName }.toSet()
                    }
                    val collator = Collator.getInstance()
                    packages.map { pkg ->
                        val info = com.aistra.hail.utils.HPackages.getApplicationInfoOrNull(pkg)
                        AppRow(pkg, info?.loadLabel(app.packageManager)?.toString() ?: pkg)
                    }.sortedWith { a, b -> collator.compare(a.label, b.label) }
                }
                items.clear(); items.addAll(loaded); rows.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { errorView.setText(R.string.editor_load_failed) }
        }
        return MaterialAlertDialogBuilder(context)
            .setTitle(if (draft.profile) R.string.profile_manage else R.string.rule_manage)
            .setView(scroll).setPositiveButton(R.string.action_save, null)
            .setNegativeButton(android.R.string.cancel, null).create()
    }

    override fun onStart() {
        super.onStart()
        (dialog as androidx.appcompat.app.AlertDialog).getButton(-1).setOnClickListener {
            val snapshot = draft
            operations.run {
                AppDataAccess.access {
                    if (snapshot.profile) {
                        val value = snapshot.buildProfile(System.currentTimeMillis(), app.packageName)
                        val store = FreezeProfileStore(app)
                        if (snapshot.isNew) {
                            check(store.getAll().none { it.id == value.id }) { "Profile already exists" }
                            store.save(value)
                        } else store.update(snapshot.originalProfile(), value)
                        EnhancedResult.Saved(value)
                    } else {
                        val value = snapshot.buildRule(app.packageName)
                        FreezeRuleStore(app).updateRules { current ->
                            if (snapshot.isNew) check(current.none { it.id == value.id }) { "Rule already exists" }
                            else check(current.firstOrNull { it.id == value.id } == snapshot.originalRule()) {
                                "Rule changed or was deleted; reopen the editor"
                            }
                            current.filterNot { it.id == value.id } + value
                        }
                        EnhancedResult.Saved()
                    }
                }
            }
        }
    }

    private fun spinner(labels: List<String>, selected: Int, change: (Int) -> Unit) = Spinner(requireContext()).apply {
        adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(selected)
        minimumHeight = dp(48)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = change(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private inner class AppRows : RecyclerView.Adapter<RowHolder>() {
        private var filtered: List<AppRow> = emptyList()
        fun refresh() {
            filtered = items.filter { it.label.contains(draft.search, true) || it.pkg.contains(draft.search, true) }
            notifyDataSetChanged()
        }
        override fun getItemCount() = filtered.size
        override fun onViewRecycled(holder: RowHolder) { iconJobs.remove(holder.icon)?.cancel() }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RowHolder {
            val row = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL; minimumHeight = dp(64) }
            val icon = ImageView(parent.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val selected = CheckBox(parent.context)
            val frozen = MaterialSwitch(parent.context)
            row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(selected, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(frozen, LinearLayout.LayoutParams(-2, -2))
            return RowHolder(row, icon, selected, frozen)
        }
        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = filtered[position]
            iconJobs.remove(holder.icon)?.cancel()
            holder.icon.setImageDrawable(null)
            iconJobs[holder.icon] = lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        val info = com.aistra.hail.utils.HPackages.getApplicationInfoOrNull(item.pkg)
                        info?.let { com.aistra.hail.utils.AppIconCache.getOrLoadBitmap(app, it,
                            com.aistra.hail.utils.HPackages.myUserId, dp(40)) }
                    }.getOrNull()
                }
                holder.icon.setImageBitmap(bitmap)
            }
            holder.selected.setOnCheckedChangeListener(null)
            holder.frozen.setOnCheckedChangeListener(null)
            holder.selected.text = getString(R.string.editor_app_row, item.label, item.pkg)
            holder.selected.isChecked = item.pkg in draft.states
            holder.frozen.visibility = if (draft.profile) View.VISIBLE else View.GONE
            holder.frozen.isEnabled = holder.selected.isChecked
            holder.frozen.isChecked = draft.states[item.pkg] == true
            holder.frozen.contentDescription = getString(R.string.profile_frozen_state, item.label)
            holder.selected.setOnCheckedChangeListener { _, checked ->
                draft = draft.copy(states = if (checked) draft.states + (item.pkg to holder.frozen.isChecked) else draft.states - item.pkg)
                holder.frozen.isEnabled = checked
            }
            holder.frozen.setOnCheckedChangeListener { _, checked ->
                if (item.pkg in draft.states) draft = draft.copy(states = draft.states + (item.pkg to checked))
            }
        }
    }
    private class RowHolder(view: View, val icon: ImageView, val selected: CheckBox,
                            val frozen: MaterialSwitch) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    companion object {
        const val RESULT = "enhanced-editor-saved"
        internal fun create(draft: EnhancedEditorDraft) = EnhancedEditorDialogFragment().apply {
            arguments = Bundle().apply { putString("draft", draft.encode()) }
        }
    }
}
