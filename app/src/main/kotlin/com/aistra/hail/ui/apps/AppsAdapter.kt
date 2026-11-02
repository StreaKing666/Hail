package com.aistra.hail.ui.apps

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ItemAppsBinding
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

class AppsAdapter : ListAdapter<ApplicationInfo, AppsAdapter.ViewHolder>(DIFF) {

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
                    privateFlags(oldItem) == privateFlags(newItem)

            private fun privateFlags(info: ApplicationInfo): Int = runCatching {
                ApplicationInfo::class.java.getDeclaredField("privateFlags").apply {
                    isAccessible = true
                }.getInt(info)
            }.getOrDefault(0)
        }
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
            val frozen = AppManager.isAppFrozen(pkg)

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
                if (HPackages.isAppUninstalled(pkg)) setTextColor(
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
                    if (runCatching { HPackages.isAppHidden(pkg) }.getOrDefault(false)) add(context.getString(com.aistra.hail.R.string.state_hidden))
                    if (runCatching { HPackages.isAppSuspended(pkg) }.getOrDefault(false)) add(context.getString(com.aistra.hail.R.string.state_suspended))
                }
                text = (listOf(pkg) + statuses).joinToString(" · ")
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