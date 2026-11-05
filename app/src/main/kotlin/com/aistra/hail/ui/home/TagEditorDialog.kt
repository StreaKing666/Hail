package com.aistra.hail.ui.home

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.app.TagAssignment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.abs

/**
 * Material bottom sheet: all tag and app changes are staged and written only on Save.
 * Closing this sheet, including Android Back or a swipe, discards every pending change.
 */
class TagEditorDialog(private val home: HomeFragment) {
    private val context: Context get() = home.requireContext()
    private val session = TagEditSession(
        HailData.tags.toList(),
        HailData.checkedList.map { TagAssignment(it.packageName, it.tagIdList.toList()) }
    )
    private lateinit var sheet: BottomSheetDialog
    private lateinit var list: RecyclerView
    private lateinit var adapter: TagAdapter
    private lateinit var summary: TextView
    private lateinit var saveButton: MaterialButton
    private lateinit var touchHelper: ItemTouchHelper

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(16))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            text = context.getString(R.string.action_manage_tags)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 22f
        }
        summary = TextView(context).apply { textSize = 13f; alpha = .72f }
        headerText.addView(title)
        headerText.addView(summary)
        header.addView(headerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = textAction("×", context.getString(android.R.string.cancel)).apply {
            textSize = 28f
            setOnClickListener { sheet.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        root.addView(header)

        val hint = TextView(context).apply {
            text = context.getString(R.string.tag_editor_v2_hint)
            textSize = 13f
            alpha = .8f
            setPadding(0, context.dp(10), 0, context.dp(10))
        }
        root.addView(hint)

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            setPadding(0, context.dp(2), 0, context.dp(8))
            itemAnimator = null
        }
        adapter = TagAdapter()
        list.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int = if (viewHolder.bindingAdapterPosition <= 0) makeMovementFlags(0, 0)
                else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (!session.move(from, to)) return false
                adapter.notifyItemMoved(from, to)
                adapter.notifyItemRangeChanged(minOf(from, to), abs(from - to) + 1)
                refreshSummary()
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).also { it.attachToRecyclerView(list) }

        val screenHeight = context.resources.displayMetrics.heightPixels
        val listHeight = (screenHeight * 0.46).toInt().coerceIn(context.dp(220), context.dp(520))
        root.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight)
        )

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addButton = MaterialButton(context).apply {
            text = context.getString(R.string.action_tag_add)
            isAllCaps = false
            setOnClickListener { promptTagName(null) }
        }
        saveButton = MaterialButton(context).apply {
            text = context.getString(R.string.action_save)
            isAllCaps = false
            setOnClickListener { save() }
        }
        footer.addView(
            addButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = context.dp(6)
            }
        )
        footer.addView(
            saveButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dp(6)
            }
        )
        root.addView(footer)

        sheet = BottomSheetDialog(context)
        sheet.setContentView(root)
        sheet.setOnShowListener {
            sheet.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }
        }
        refreshSummary()
        sheet.show()
    }

    private fun refreshSummary() {
        val appCount = session.allAssignments().values.count { it.isNotEmpty() }
        summary.text = context.getString(
            R.string.tag_editor_v2_summary, session.tags.size, appCount
        ) + if (session.isDirty) " • " + context.getString(R.string.tag_editor_v2_pending) else ""
        saveButton.isEnabled = session.isDirty
    }

    private fun manageApps(tagId: Int) {
        val tag = session.tags.firstOrNull { it.second == tagId } ?: return
        TagAppManagerDialog(context, session, tag) {
            adapter.notifyDataSetChanged()
            refreshSummary()
        }.show()
    }

    private fun promptTagName(tagId: Int?) {
        val old = tagId?.let { id -> session.tags.firstOrNull { it.second == id } }
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(old?.first.orEmpty())
            setSelection(text?.length ?: 0)
        }
        val field = TextInputLayout(context).apply {
            hint = context.getString(R.string.tag)
            addView(input)
        }
        val container = FrameLayout(context).apply {
            setPadding(context.dp(24), context.dp(6), context.dp(24), 0)
            addView(field)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (old == null) R.string.action_tag_add else R.string.action_tag_rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    field.error = context.getString(R.string.tag_name_required)
                    return@setOnClickListener
                }
                val changed = if (tagId == null) session.addTag(name) != null
                    else session.renameTag(tagId, name)
                if (!changed) {
                    field.error = context.getString(R.string.tag_duplicate_name)
                    return@setOnClickListener
                }
                adapter.notifyDataSetChanged()
                list.scrollToPosition(session.tags.lastIndex.takeIf { tagId == null }
                    ?: session.tags.indexOfFirst { it.second == tagId }.coerceAtLeast(0))
                refreshSummary()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(tagId: Int) {
        val tag = session.tags.firstOrNull { it.second == tagId } ?: return
        if (tagId == 0) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.action_tag_remove)
            .setMessage(context.getString(R.string.tag_delete_confirm, tag.first))
            .setPositiveButton(R.string.action_tag_remove) { _, _ ->
                if (session.deleteTag(tagId)) {
                    adapter.notifyDataSetChanged()
                    refreshSummary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun actionMenu(anchor: View, tagId: Int) {
        val index = session.tags.indexOfFirst { it.second == tagId }
        if (index < 0) return
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, R.string.tag_manage_apps)
        if (tagId != 0) {
            popup.menu.add(0, 2, 1, R.string.action_tag_rename)
            if (index > 1) popup.menu.add(0, 3, 2, R.string.tag_move_up)
            if (index < session.tags.lastIndex) popup.menu.add(0, 4, 3, R.string.tag_move_down)
            popup.menu.add(0, 5, 4, R.string.action_tag_remove)
        }
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> manageApps(tagId)
                2 -> promptTagName(tagId)
                3, 4 -> {
                    val at = session.tags.indexOfFirst { pair -> pair.second == tagId }
                    val next = at + if (it.itemId == 3) -1 else 1
                    if (session.move(at, next)) {
                        adapter.notifyItemMoved(at, next)
                        adapter.notifyDataSetChanged()
                        refreshSummary()
                    }
                }
                5 -> confirmDelete(tagId)
            }
            true
        }
        popup.show()
    }

    private fun save() {
        if (!session.isDirty) {
            sheet.dismiss()
            return
        }
        // Capture identity before mutating the global tab order; the selected index may move.
        try {
            com.aistra.hail.app.AppDataAccess.access { saveChanges() }
        } catch (error: Exception) {
            com.aistra.hail.utils.HUI.showToast(com.aistra.hail.R.string.operation_failed, com.aistra.hail.utils.HUI.operationError(error), true)
        }
    }

    private fun saveChanges() {
        val chosenId = home.selectedTagId()
        val prevTags = HailData.tags.toList()
        val prevApps = HailData.checkedList.toList()
        val prevAssignments = prevApps.associate { it.packageName to it.tagIdList.toList() }
        val nextAssignments = session.allAssignments()
        val newlyUnassigned = session.newlyUnassignedPackages()
        try {
            HailData.tags.clear()
            HailData.tags.addAll(session.tags)
            HailData.checkedList.forEach { app ->
                // A package added while this sheet was open is not part of the edit session.
                // Preserve its existing assignment rather than silently removing it.
                val editedTags = nextAssignments[app.packageName] ?: return@forEach
                app.tagIdList.clear()
                app.tagIdList.addAll(editedTags)
            }
            // Remove only apps orphaned by this edit, never preexisting/unrelated records.
            HailData.checkedList.removeAll { it.packageName in newlyUnassigned }
            HailData.saveTags()
            HailData.saveApps()
        } catch (error: Exception) {
            HailData.tags.clear()
            HailData.tags.addAll(prevTags)
            HailData.checkedList.clear()
            HailData.checkedList.addAll(prevApps)
            prevApps.forEach { app ->
                app.tagIdList.clear()
                app.tagIdList.addAll(prevAssignments[app.packageName].orEmpty())
            }
            runCatching { HailData.saveTags(); HailData.saveApps() }
            Toast.makeText(
                context,
                context.getString(
                    R.string.tag_backup_error, error.message ?: error.javaClass.simpleName
                ), Toast.LENGTH_LONG
            ).show()
            return
        }
        home.refreshTags(if (session.tags.any { it.second == chosenId }) chosenId else 0)
        home.refreshAppLists()
        sheet.dismiss()
    }

    private inner class TagAdapter : RecyclerView.Adapter<TagHolder>() {
        override fun getItemCount() = session.tags.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagHolder {
            val card = MaterialCardView(context).apply {
                minimumHeight = context.dp(84)
                radius = context.dp(16).toFloat()
                strokeWidth = context.dp(1)
                cardElevation = 0f
                useCompatPadding = false
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = context.dp(8) }
            }
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            }
            val handle = textAction("⠿", context.getString(R.string.tag_drag)).apply {
                textSize = 24f
            }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(6), 0, 0, 0)
            }
            val title = TextView(context).apply {
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subtitle = TextView(context).apply { textSize = 12f; alpha = .72f }
            labels.addView(title)
            labels.addView(subtitle)
            val more = textAction("⋮", context.getString(R.string.tag_editor_v2_actions)).apply {
                textSize = 26f
            }
            row.addView(handle, LinearLayout.LayoutParams(context.dp(44), context.dp(52)))
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(more, LinearLayout.LayoutParams(context.dp(48), context.dp(52)))
            card.addView(row)
            return TagHolder(card, handle, title, subtitle, more)
        }

        override fun onBindViewHolder(holder: TagHolder, position: Int) {
            val tag = session.tags[position]
            val fixed = tag.second == 0
            holder.title.text = tag.first
            holder.subtitle.text = context.resources.getQuantityString(
                R.plurals.tag_app_count, session.appCount(tag.second), session.appCount(tag.second)
            ) + if (fixed) " • " + context.getString(R.string.tag_editor_v2_fixed) else ""
            holder.handle.text = if (fixed) "▣" else "⠿"
            holder.handle.contentDescription = context.getString(
                if (fixed) R.string.tag_default_locked else R.string.tag_drag
            )
            holder.handle.isEnabled = !fixed
            holder.handle.alpha = if (fixed) .45f else 1f
            holder.handle.setOnClickListener { actionMenu(holder.handle, tag.second) }
            var touchX = 0f
            var touchY = 0f
            var moved = false
            holder.handle.setOnTouchListener { handle, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchX = event.x; touchY = event.y; moved = false
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                    (kotlin.math.abs(event.x - touchX) + kotlin.math.abs(event.y - touchY)) >
                    android.view.ViewConfiguration.get(context).scaledTouchSlop) moved = true
                if (event.actionMasked == MotionEvent.ACTION_UP && !moved) handle.performClick()
                if (!fixed && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                    true
                } else false
            }
            holder.itemView.setOnClickListener { manageApps(tag.second) }
            holder.more.setOnClickListener { actionMenu(holder.more, tag.second) }
        }
    }

    private class AccessibleActionTextView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
        override fun performClick(): Boolean = super.performClick()
    }

    private fun textAction(symbol: String, description: String) = AccessibleActionTextView(context).apply {
        text = symbol
        textSize = 20f
        gravity = Gravity.CENTER
        contentDescription = description
        isClickable = true
        isFocusable = true
        minWidth = context.dp(44)
        minHeight = context.dp(48)
        setBackgroundResource(android.R.drawable.list_selector_background)
    }

    private class TagHolder(
        item: View,
        val handle: AccessibleActionTextView,
        val title: TextView,
        val subtitle: TextView,
        val more: TextView
    ) : RecyclerView.ViewHolder(item)
}

private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
