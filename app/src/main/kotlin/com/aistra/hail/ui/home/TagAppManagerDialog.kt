package com.aistra.hail.ui.home

import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HPackages
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

internal class TagAppManagerDialog(
    private val context: Context,
    private val session: TagEditSession,
    private val targetTag: Pair<String, Int>,
    private val onChanged: () -> Unit
) {
    private val sourceTags = session.tags.filter { it.second != targetTag.second }
    private val selectedPackages = linkedSetOf<String>()
    private var visibleApps: List<AppInfo> = emptyList()

    private lateinit var sourceSpinner: Spinner
    private lateinit var searchInput: TextInputEditText
    private lateinit var selectedCount: TextView
    private lateinit var selectVisibleButton: MaterialButton
    private lateinit var emptyView: TextView
    private lateinit var addButton: MaterialButton
    private lateinit var moveButton: MaterialButton
    private lateinit var adapter: AppAdapter

    fun show(): Dialog? {
        if (sourceTags.isEmpty()) {
            return MaterialAlertDialogBuilder(context)
                .setTitle(R.string.tag_manage_apps)
                .setMessage(R.string.tag_apps_no_source_categories)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(20))
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.tag_manage_apps)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, context.dp(8))
        }
        content.addView(title)
        val targetLabel = TextView(context).apply {
            text = context.getString(R.string.tag_apps_target, targetTag.first)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, context.dp(8))
        }
        val sourceLabel = TextView(context).apply {
            text = context.getString(R.string.tag_source_category)
            textSize = 13f
            alpha = 0.8f
        }
        sourceSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                sourceTags.map { it.first }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val searchLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.tag_apps_search)
            addView(
                TextInputEditText(context).also { searchInput = it },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        selectedCount = TextView(context).apply {
            textSize = 12f
            alpha = 0.78f
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        selectVisibleButton = MaterialButton(context).apply {
            text = context.getString(R.string.tag_select_all_visible)
            setOnClickListener { toggleVisibleSelection() }
        }

        adapter = AppAdapter()
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@TagAppManagerDialog.adapter
            setHasFixedSize(false)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        emptyView = TextView(context).apply {
            text = context.getString(R.string.tag_apps_empty)
            gravity = Gravity.CENTER
            textSize = 14f
            minHeight = context.dp(96)
            visibility = View.GONE
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                recyclerView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(280)
                )
            )
            addView(
                emptyView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(96)
                )
            )
        }

        addButton = MaterialButton(context).apply {
            text = context.getString(R.string.tag_apps_add)
            setOnClickListener { applyAssignment(move = false) }
        }
        moveButton = MaterialButton(context).apply {
            text = context.getString(R.string.tag_apps_move)
            setOnClickListener { applyAssignment(move = true) }
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                addButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = context.dp(6)
                }
            )
            addView(
                moveButton,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = context.dp(6)
                }
            )
        }

        content.addView(targetLabel)
        content.addView(sourceLabel)
        content.addView(
            sourceSpinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(48)
            )
        )
        content.addView(
            searchLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(selectVisibleButton)
        content.addView(selectedCount)
        content.addView(listContainer)
        content.addView(actions)

        val footerHint = TextView(context).apply {
            text = context.getString(R.string.tag_editor_v2_staged)
            textSize = 12f
            alpha = .72f
            setPadding(0, context.dp(6), 0, 0)
        }
        content.addView(footerHint)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
        }

        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                selectedPackages.clear()
                refreshApps()
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshApps()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.show()
        refreshApps()
        return dialog
    }

    private fun currentSourceTag(): Pair<String, Int>? =
        sourceTags.getOrNull(sourceSpinner.selectedItemPosition)

    private fun refreshApps() {
        val sourceId = currentSourceTag()?.second ?: return
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val sourceApps = HailData.checkedList
            .filter { it.userId == HPackages.myUserId && sourceId in session.tagsFor(it.packageName) }
            .sortedBy { it.name.toString().lowercase() }
        visibleApps = sourceApps.filter { app ->
            query.isEmpty() ||
                app.name.toString().contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
        adapter.submit(visibleApps)
        emptyView.visibility = if (visibleApps.isEmpty()) View.VISIBLE else View.GONE
        selectVisibleButton.isEnabled = visibleApps.isNotEmpty()
        updateSelectionUi()
    }

    private fun toggleVisibleSelection() {
        if (visibleApps.isEmpty()) return
        val allSelected = visibleApps.all { it.packageName in selectedPackages }
        if (allSelected) visibleApps.forEach { selectedPackages.remove(it.packageName) }
        else visibleApps.forEach { selectedPackages.add(it.packageName) }
        adapter.notifyDataSetChanged()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val sourceId = currentSourceTag()?.second
        val eligibleSelected = if (sourceId == null) 0 else selectedPackages.count { packageName ->
            HailData.checkedList.any {
                it.userId == HPackages.myUserId && it.packageName == packageName && sourceId in session.tagsFor(it.packageName)
            }
        }
        selectedCount.text = context.getString(R.string.tag_selected_count, eligibleSelected)
        val allSelected = visibleApps.isNotEmpty() &&
            visibleApps.all { it.packageName in selectedPackages }
        selectVisibleButton.text = context.getString(
            if (allSelected) R.string.tag_clear_visible_selection
            else R.string.tag_select_all_visible
        )
        addButton.isEnabled = eligibleSelected > 0
        moveButton.isEnabled = eligibleSelected > 0
    }

    private fun applyAssignment(move: Boolean) {
        val sourceId = currentSourceTag()?.second ?: return
        val selected = selectedPackages.toSet()
        if (selected.isEmpty()) return
        val count = session.assign(sourceId, targetTag.second, selected, move)
        selectedPackages.clear()
        if (count > 0) onChanged()
        refreshApps()

        Toast.makeText(
            context,
            context.getString(
                if (move) R.string.tag_apps_move_success else R.string.tag_apps_add_success,
                count
            ) + " · " + context.getString(R.string.tag_editor_v2_pending),
            Toast.LENGTH_SHORT
        ).show()
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppViewHolder>() {
        private var items: List<AppInfo> = emptyList()

        fun submit(next: List<AppInfo>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(56)
                setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val checkBox = CheckBox(context).apply {
                minWidth = context.dp(48)
                minHeight = context.dp(48)
            }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(context).apply {
                textSize = 14f
                maxLines = 1
            }
            val subtitle = TextView(context).apply {
                textSize = 11f
                alpha = 0.68f
                maxLines = 1
            }
            labels.addView(title)
            labels.addView(subtitle)
            row.addView(checkBox)
            row.addView(
                labels,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            return AppViewHolder(row, checkBox, title, subtitle)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val app = items[position]
            holder.title.text = app.name
            holder.subtitle.text = app.packageName
            holder.checkBox.contentDescription = app.name.toString()
            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = app.packageName in selectedPackages
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedPackages.add(app.packageName)
                else selectedPackages.remove(app.packageName)
                updateSelectionUi()
            }
            holder.itemView.setOnClickListener {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
            }
        }
    }

    private class AppViewHolder(
        row: View,
        val checkBox: CheckBox,
        val title: TextView,
        val subtitle: TextView
    ) : RecyclerView.ViewHolder(row)
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
