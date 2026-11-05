package com.aistra.hail.ui.home

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.aistra.hail.app.HailData.tags
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.app.RecoveryStatus

class HomeAdapter(fragment: HomeFragment) : FragmentStateAdapter(fragment) {
    // Adapter data changes only when the UI publishes the next snapshot and notifies it.
    private var visibleTags = publishableTags()
    fun refreshTags() { visibleTags = publishableTags() }
    private fun publishableTags() = if (AppDataAccess.status.value == RecoveryStatus.READY) tags.toList() else emptyList()
    fun tagAt(position: Int) = visibleTags.getOrNull(position)

    override fun getItemCount(): Int = visibleTags.size

    override fun getItemId(position: Int): Long = TagOrder.stableItemId(visibleTags[position].second)

    override fun containsItem(itemId: Long): Boolean =
        visibleTags.any { TagOrder.stableItemId(it.second) == itemId }

    override fun createFragment(position: Int): Fragment = PagerFragment()
}
