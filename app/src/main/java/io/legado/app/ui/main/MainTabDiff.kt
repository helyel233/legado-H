package io.legado.app.ui.main

import androidx.recyclerview.widget.DiffUtil

/**
 * Pure-JVM DiffUtil callback for [MainActivity] bottom navigation tabs.
 * Each visible tab is uniquely identified by its fragment ID so unchanged tabs
 * preserve their Fragment instances across bottom menu updates.
 *
 * [MainActivity]'s `FragmentStateAdapter` routes purely on `fragmentId` identity;
 * [areContentsTheSame] intentionally mirrors [areItemsTheSame] because tab icons
 * and titles are updated in-place on `BottomNavigationView` without recreating Fragments.
 */
class MainTabDiffCallback(
    private val oldIds: List<Int>,
    private val newIds: List<Int>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldIds.size

    override fun getNewListSize(): Int = newIds.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldIds[oldItemPosition] == newIds[newItemPosition]
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldIds[oldItemPosition] == newIds[newItemPosition]
    }
}

object MainTabDiff {

    fun calculateDiff(oldIds: List<Int>, newIds: List<Int>): DiffUtil.DiffResult {
        return DiffUtil.calculateDiff(MainTabDiffCallback(oldIds, newIds))
    }
}
