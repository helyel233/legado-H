package io.legado.app.ui.rss.favorites


import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.databinding.FragmentRssArticlesBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.undoSnackbar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class RssFavoritesFragment() : VMBaseFragment<RssFavoritesViewModel>(R.layout.fragment_rss_articles),
    RssFavoritesAdapter.CallBack {

    constructor(group: String) : this() {
        arguments = Bundle().apply {
            putString("group", group)
        }
    }

    private val binding by viewBinding(FragmentRssArticlesBinding::bind)
    override val viewModel by viewModels<RssFavoritesViewModel>()
    private val adapter: RssFavoritesAdapter by lazy {
        RssFavoritesAdapter(requireContext(), this@RssFavoritesFragment)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        loadArticles()
    }

    private fun initView() = binding.run {
        refreshLayout.isEnabled = false
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = run {
            recyclerView.addItemDecoration(VerticalDivider(requireContext()))
            LinearLayoutManager(requireContext())
        }
        recyclerView.adapter = adapter
        recyclerView.applyNavigationBarPadding()
        tvEmpty.setTextColor(secondaryTextColor)
    }

    private fun loadArticles() {
        // 必须用 viewLifecycleOwner：Fragment 宿主在 ViewPager 里视图会销毁重建，
        // 用 lifecycleScope 会在视图销毁后继续 collect 并对已 detach 的 RecyclerView setItems
        viewLifecycleOwner.lifecycleScope.launch {
            val group = arguments?.getString("group") ?: "默认分组"
            appDb.rssStarDao.flowByGroup(group).catch {
                AppLog.put("订阅文章界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
                // 空分组原本是一片空白，用户不知道是加载失败还是本来就没收藏
                showEmpty(it.isEmpty())
            }
        }
    }

    private fun showEmpty(empty: Boolean) = binding.run {
        if (empty) {
            tvEmpty.setText(R.string.rss_favorites_empty)
            tvEmpty.visible()
            recyclerView.gone()
        } else {
            tvEmpty.gone()
            recyclerView.visible()
        }
    }

    override fun readRss(rssStar: RssStar) {
        ReadRss.readRss(this, rssStar.toRssArticle())
    }

    override fun delStar(rssStar: RssStar) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n<" + rssStar.title + ">")
            noButton()
            yesButton {
                appDb.rssStarDao.delete(rssStar.origin, rssStar.link)
                // 收藏删掉就找不回来了，给一个撤销窗口
                binding.root.undoSnackbar(
                    getString(R.string.deleted_rss_favorites),
                    getString(R.string.undo)
                ) {
                    lifecycleScope.launch(IO) {
                        appDb.rssStarDao.insert(rssStar)
                    }
                }
            }
        }
    }
}
