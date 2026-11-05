package com.aistra.hail.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.aistra.hail.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A restorable modal prevents edits/navigation while a multi-file operation is running. */
class OperationProgressDialog : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); isCancelable = false }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.operation_in_progress).setView(ProgressBar(requireContext())).create()
    companion object {
        fun update(manager: FragmentManager, busy: Boolean) {
            val current = manager.findFragmentByTag("enhanced-operation") as? OperationProgressDialog
            if (manager.isStateSaved) return
            if (busy && current == null) OperationProgressDialog().showNow(manager, "enhanced-operation")
            else if (!busy) current?.dismiss()
        }
    }
}
