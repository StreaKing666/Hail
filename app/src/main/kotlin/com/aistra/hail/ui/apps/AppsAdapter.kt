package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.app.observeFrozenState
import com.aistra.hail.app.FreezePresentationPolicy
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ItemAppsBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

class AppsAdapter private constructor(
    private val presentedStates: MutableMap<String, FreezePresentationPolicy.State>
) : ListAdapter<ApplicationInfo, AppsAdapter.ViewHolder>(ObservationDiff(presentedStates)) {
    constructor() : this(ConcurrentHashMap())

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ApplicationInfo>() {
            override fun areItemsTheSame(
                oldItem: ApplicationInfo, newItem: ApplicationInfo
            ): Boolean = oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: ApplicationInfo, newItem: ApplicationInfo): Boolean =
                oldItem.flags == newItem.flags &&
                    oldItem.enabled == newItem.enabled &&
                    oldItem.uid == newItem.uid &&
                    oldItem.sourceDir == newItem.sourceDir &&
                    HPackages.privateFlagsOrNull(oldItem) == HPackages.privateFlagsOrNull(newItem)
        }
    }

    // A mode change can alter verification without changing ApplicationInfo's public flags.
    private class ObservationDiff(
        private val presentedStates: Map<String, FreezePresentationPolicy.State>
    ) : DiffUtil.ItemCallback<ApplicationInfo>() {
        override fun areItemsTheSame(oldItem: ApplicationInfo, newItem: ApplicationInfo): Boolean =
            DIFF.areItemsTheSame(oldItem, newItem)

        override fun areContentsTheSame(oldItem: ApplicationInfo, newItem: ApplicationInfo): Boolean =
            DIFF.areContentsTheSame(oldItem, newItem) &&
                presentedStates[oldItem.packageName] == FreezePresentationPolicy.classify(
                    newItem.uid / 100_000 == HPackages.myUserId, true, newItem.observeFrozenState())
    }

    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemCheckedChangeListener: OnItemCheckedChangeListener
    private var loadIconJob: Job? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemAppsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = currentList[position]
        holder.bindInfo(info)
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    inner class ViewHolder(private val binding: ItemAppsBinding) : RecyclerView.ViewHolder(binding.root) {
        lateinit var info: ApplicationInfo
        private val pkg get() = info.packageName

        /**
         * Flag that view data is being updated to avoid triggering the event.
         * */
        private var updating = false

        init {
            binding.root.apply {
                setOnClickListener { onItemClickListener.onItemClick(binding.appStar) }
                isLongClickable = true
            }
            binding.appStar.setOnCheckedChangeListener { button, isChecked ->
                if (!updating) onItemCheckedChangeListener.onItemCheckedChange(button, isChecked, pkg)
            }
        }

        fun bindInfo(info: ApplicationInfo) {
            updating = true
            this.info = info
            val observed = info.observeFrozenState()
            val state = FreezePresentationPolicy.classify(
                info.uid / 100_000 == HPackages.myUserId, true, observed)
            presentedStates[pkg] = state
            val frozen = state == FreezePresentationPolicy.State.FROZEN

            binding.appIcon.apply {
                val baseSize = resources.getDimensionPixelSize(com.aistra.hail.R.dimen.app_icon_size)
                val targetSize = if (HailData.compactAppList) (baseSize * 0.75f).toInt() else baseSize
                layoutParams = layoutParams.apply {
                    width = targetSize
                    height = targetSize
                }
                loadIconJob = AppIconCache.loadIconBitmapAsync(
                    context, info, HPackages.myUserId, this, HailData.grayscaleIcon && frozen
                )
            }
            binding.appName.apply {
                val name = info.loadLabel(context.packageManager)
                text = if (!HailData.grayscaleIcon && frozen) "❄️$name" else name
                isEnabled = !HailData.grayscaleIcon || !frozen
                if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) setTextColor(
                    MaterialColors.getColor(
                        this, androidx.appcompat.R.attr.colorError
                    )
                )
                else setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            }
            binding.appDesc.apply {
                val statuses = buildList {
                    if (info.flags and ApplicationInfo.FLAG_INSTALLED == 0) add(context.getString(com.aistra.hail.R.string.state_uninstalled))
                    if (!info.enabled) add(context.getString(com.aistra.hail.R.string.state_disabled))
                    if (HPackages.isAppHidden(info) == true) add(context.getString(com.aistra.hail.R.string.state_hidden))
                    if (HPackages.isAppSuspended(info)) add(context.getString(com.aistra.hail.R.string.state_suspended))
                }
                val verification = if (observed == null)
                    listOf(context.getString(com.aistra.hail.R.string.state_unverified)) else emptyList()
                // Put the diagnostic first so compact rows cannot ellipsize it behind the package name.
                text = (verification + pkg + statuses).joinToString(" · ")
                maxLines = if (HailData.compactAppList) 1 else 2
                isEnabled = !HailData.grayscaleIcon || !frozen
            }
            binding.appStar.isChecked = HailData.isChecked(pkg)
            updating = false
        }
    }

    interface OnItemClickListener {
        fun onItemClick(buttonView: CompoundButton)
    }

    interface OnItemCheckedChangeListener {
        fun onItemCheckedChange(buttonView: CompoundButton, isChecked: Boolean, packageName: String)
    }
}