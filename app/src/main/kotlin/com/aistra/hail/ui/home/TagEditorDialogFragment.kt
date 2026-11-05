package com.aistra.hail.ui.home

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aistra.hail.utils.HPackages
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class TagEditorDialogFragment : BottomSheetDialogFragment() {
    private val model: TagEditorViewModel by viewModels()
    private var editor: TagEditorDialog? = null
    private var container: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.initialize(savedInstanceState?.getString(DRAFT),
            savedInstanceState?.getBoolean(DRAFT_UNAVAILABLE) == true,
            savedInstanceState?.getBoolean(SAVE_PENDING) == true,
            savedInstanceState?.getInt(USER_ID, -1))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { state ->
                    isCancelable = !state.busy
                    if (state.saved) {
                        parentFragmentManager.setFragmentResult(RESULT, Bundle().apply {
                            putBoolean(LAUNCHER_WARNING, state.launcherWarning)
                        })
                        model.consumeSaved()
                        dismiss()
                        return@collect
                    }
                    if (state.session != null && editor == null) {
                        editor = TagEditorDialog(requireContext(), state.session, model::save, ::dismiss).also {
                            container?.removeAllViews()
                            container?.addView(it.createView())
                        }
                    }
                    editor?.setStatus(state.busy, state.error)
                    if (state.session == null && !state.busy) {
                        container?.removeAllViews()
                        container?.addView(TextView(requireContext()).apply {
                            text = state.error
                            setPadding(48, 48, 48, 48)
                        })
                    }
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val content = FrameLayout(requireContext()).apply {
            addView(ProgressBar(context), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ).apply { topMargin = 48; bottomMargin = 48 })
        }
        container = content
        return BottomSheetDialog(requireContext(), theme).apply { setContentView(content) }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val session = model.state.value.session
        if (session != null) {
            runCatching { session.exportSnapshot() }.onSuccess { outState.putString(DRAFT, it) }
                .onFailure { outState.putBoolean(DRAFT_UNAVAILABLE, true) }
        }
        outState.putBoolean(SAVE_PENDING, model.state.value.busy && session != null)
        outState.putInt(USER_ID, session?.userId ?: HPackages.myUserId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        editor?.dispose()
        editor = null
        container = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "advanced-tag-editor"
        const val RESULT = "advanced-tag-editor-result"
        const val LAUNCHER_WARNING = "launcherWarning"
        private const val DRAFT = "tagDraft"
        private const val DRAFT_UNAVAILABLE = "tagDraftUnavailable"
        private const val SAVE_PENDING = "tagSavePending"
        private const val USER_ID = "tagEditorUserId"
    }
}
