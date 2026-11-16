@file:Suppress("DEPRECATION")

package io.legado.app.ui.rss.favorites

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityRssFavoritesBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.utils.gone
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 收藏夹
 */
class RssFavoritesActivity : BaseActivity<ActivityRssFavoritesBinding>() {

    override val binding by viewBinding(ActivityRssFavoritesBinding::inflate)
    private val adapter by lazy { TabFragmentPageAdapter() }
    private var groupList = mutableListOf<String>()
    /** 「分组」自绘菜单（ModernActionPopup）的句柄，销毁时收起防泄漏。 */
    private var groupsMenuPopup: ModernActionPopup.Handle? = null
    private var currentGroup = ""

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.applyWallpaperGlass()
        initView()
        upFragments()
    }

    override fun onResume() {
        super.onResume()
        //从ReadRssActivity退出时，判断是否需要重新定位tabLayout选中项
        if (currentGroup.isNotEmpty() && groupList.isNotEmpty()) {
            var item = groupList.indexOf(currentGroup)
            val currentItem = binding.viewPager.currentItem
            //如果坐标没有变化，则结束
            if (item == currentItem) {
                return
            }
            if (item == -1) {
                item = currentItem
            }
            lifecycleScope.launch {
                delay(100)
                if (isDestroyed || isFinishing) return@launch
                binding.tabLayout.getTabAt(item)?.select()
            }
        }
    }

    private fun initView() {
        binding.viewPager.adapter = adapter
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
            }

            override fun onPageSelected(position: Int) {
                // notifyDataSetChanged 后 ViewPager 可能回传越界 position
                groupList.getOrNull(position)?.let { currentGroup = it }
            }

            override fun onPageScrollStateChanged(state: Int) {}

        })
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_favorites, menu)
        // 分组列表在点击时经 onCompatOptionsItemSelected 拦截后用 ModernActionPopup
        // 渲染（见 showGroupsMenu），不再走平台子菜单——平台子菜单的行没有底色块
        // 与描边，与发现页 / 订阅源页同源的「右上菜单选项无背景色框」。
        return super.onCompatCreateOptionsMenu(menu)
    }

    /**
     * 「分组」菜单：ModernActionPopup 渲染（与书架 / 发现页 / 订阅源页同一实现与同一套
     * 行样式——半透明行底 + 描边 + 玻璃域判定）。
     *
     * 点击行为与旧子菜单逐值一致：按分组在 [groupList] 中的下标切 ViewPager 页
     * （旧实现用 `subMenu.add(groupId, NONE, index, title)` 的 order，等价于本下标）。
     * 顶部 tabLayout 已展示同样分组时，弹层与 tab 双入口并存，行为一致不冲突。
     */
    private fun showGroupsMenu() {
        if (groupList.isEmpty()) return
        val anchor = ModernActionPopup.findToolbarItemAnchor(
            binding.titleBar.toolbar, R.id.menu_group
        ) ?: binding.titleBar.toolbar
        val actions = groupList.mapIndexed { index, group ->
            ModernActionPopup.Action(title = group) {
                binding.viewPager.setCurrentItem(index)
            }
        }
        groupsMenuPopup = ModernActionPopup.show(
            anchor,
            actions,
            groupsMenuPopup
        )
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_group) {
            showGroupsMenu()
        } else {
            when (item.itemId) {
                R.id.menu_del_group -> deleteGroup()
                R.id.menu_del_all -> deleteAll()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onDestroy() {
        groupsMenuPopup?.dismiss()
        groupsMenuPopup = null
        super.onDestroy()
    }

    private fun upFragments() {
        lifecycleScope.launch {
            appDb.rssStarDao.flowGroups().catch {
                AppLog.put("订阅分组数据获取失败\n${it.localizedMessage}", it)
            }.distinctUntilChanged().flowOn(IO).collect {
                groupList.clear()
                groupList.addAll(it)
                if (groupList.size == 1) {
                    binding.tabLayout.gone()
                } else {
                    binding.tabLayout.visible()
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun deleteGroup() {
        if (groupList.isEmpty()) {
            return
        }
        alert(R.string.draw) {
            val item = binding.viewPager.currentItem
            val group = groupList.getOrNull(item) ?: return@alert
            setMessage(
                getString(R.string.sure_del) + "\n<" + group + ">" + getString(R.string.group)
            )
            noButton()
            yesButton {
                appDb.rssStarDao.deleteByGroup(group)
            }
        }
    }

    private fun deleteAll() {
        alert(R.string.draw) {
            setMessage(
                getString(R.string.sure_del) + "\n<" + getString(R.string.all) + ">"
                        + getString(R.string.favorite)
            )
            noButton()
            yesButton {
                appDb.rssStarDao.deleteAll()
            }
        }
    }

    private inner class TabFragmentPageAdapter :
        FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItemPosition(`object`: Any): Int {
            return POSITION_NONE
        }

        override fun getPageTitle(position: Int): CharSequence {
            return groupList[position]
        }

        override fun getItem(position: Int): Fragment {
            val group = groupList[position]
            return RssFavoritesFragment(group)
        }

        override fun getCount(): Int {
            return groupList.size
        }

    }
}
