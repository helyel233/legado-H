@file:Suppress("DEPRECATION")

package io.legado.app.ui.rss.article

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssArtivlesBinding
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.GlassSurface
import io.legado.app.ui.glass.wallpaperGlassSignature
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.utils.*
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.viewpager.widget.ViewPager
import io.legado.app.utils.startActivity
import splitties.init.appCtx

/** 栏目按钮的圆角，与原 `createTabBackground` 的 radius 逐值一致（16dp）。 */
private const val TAB_CORNER_RADIUS_DP = 16f

/** 选中描边线宽，与原 `createTabBackground` 的 strokeWidth 逐值一致（1dp）。 */
private const val TAB_STROKE_WIDTH_DP = 1f

/**
 * 栏目按钮玻璃签名 tag 的 keyed tag key。
 *
 * 本页只有这一种玻璃表面，取值仍按项目约定用 `0x5F` 打头：`View.setTag(int, Object)`
 * 要求 key 的应用位 >= 2（低 24 位留给框架），否则真机抛 `IllegalArgumentException`。
 */
private const val TAB_GLASS_SIGNATURE_TAG = 0x5F_54_41_42

class RssSortActivity : VMBaseActivity<ActivityRssArtivlesBinding, RssSortViewModel>(),
    VariableDialog.Callback {

    override val binding by viewBinding(ActivityRssArtivlesBinding::inflate)
    override val viewModel by viewModels<RssSortViewModel>()
    private val adapter by lazy { TabFragmentPageAdapter() }
    private var sortUrls: List<Pair<String, String>>? = null
    private val sortList = mutableListOf<Pair<String, String>>()
    private val fragmentMap = hashMapOf<String, Fragment>()
    private val orientation by lazy { resources.configuration.orientation }
    private var shouldFocusSearch = false
    private var pureSearch = false
    private var favoriteMenuItem: MenuItem? = null
    private val editSourceResult = registerForActivityResult(
        StartActivityContract(RssSourceEditActivity::class.java)
    ) {
        if (it.resultCode == RESULT_OK) {
            viewModel.initData(intent) {
                sortUrls = null
                upFragments()
            }
        }
    }

    // 添加类属性
    private val tabRows = mutableListOf<LinearLayout>()
    var maxTagsPerRow = 10 // 每行尽量容纳10个标签,横屏20
    private val tabScrollViews = mutableListOf<HorizontalScrollView>() // 添加滚动视图列表

    /**
     * 上一次给这批栏目按钮铺表面时记下的签名（[wallpaperGlassSignature]）。
     *
     * null = 还没铺过（首次进入，或刚由 [setupMultiLineTabs] 重建出新的 View）。
     * 非 null 时每次 [onResume] 只做一次字符串比较：档位没变就完全不动 drawable。
     */
    private var lastTabsGlassSignature: String? = null

    private fun setupMultiLineTabs() {
        val tabsContainer = binding.tabsContainer
        tabsContainer.removeAllViews()
        tabRows.clear()
        tabScrollViews.clear()
        // 旧的 tabView 连同它们的签名 tag 一起被丢弃了，记录必须作废，
        // 否则下面 applyTabsGlass 会以为「这一档已经铺过」而让新按钮停在默认背景上
        lastTabsGlassSignature = null
        if (sortList.isEmpty()) {
            tabsContainer.gone()
            return
        }
        // 动态计算每行标签数量,最多3行
        var rowCount = when {
            sortList.size <= 10 -> 1
            sortList.size <= 20 -> 2
            else -> 3
        }
        if (rowCount > 1 && orientation == Configuration.ORIENTATION_LANDSCAPE) rowCount-- //横屏最多2行
        maxTagsPerRow = (sortList.size + rowCount - 1) / rowCount
        sortList.chunked(maxTagsPerRow).forEachIndexed { rowIndex, rowItems ->
            // 创建横向滚动容器
            val scrollView = HorizontalScrollView(this).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 6.dpToPx()
                }
                tabScrollViews.add(this)
            }
            // 创建行容器
            val rowLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            // 添加标签到行
            rowItems.forEachIndexed { indexInRow, sort ->
                val globalIndex = rowIndex * maxTagsPerRow + indexInRow
                val tabView = createTabView(sort.first, globalIndex)
                rowLayout.addView(tabView)
            }
            scrollView.addView(rowLayout)
            tabsContainer.addView(scrollView)
            tabRows.add(rowLayout)
        }
        // 标签是新建出来的：先把表面铺好，再定选中态。先铺背景再选中可以避免
        // 第一帧出现「有选中文字、没有选中描边」的闪动
        applyTabsGlass()
        // 初始选中状态
        updateTabSelection(binding.viewPager.currentItem)
    }

    private fun createTabView(title: String, position: Int): TextView {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = uiTypeface()
            // 先铺改动前的原表面：玻璃不可用时它就是最终结果（逐值等于改前），
            // 可用时会被 applyTabGlass 整体换成「玻璃 + 描边层」
            background = createTabBackground(accentColor)
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            tag = position
            setTextColor(context.primaryTextColor)
            // 宽度自适应内容
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 6.dpToPx()
            }
            setOnClickListener {
                binding.viewPager.currentItem = position
                updateTabSelection(position)
            }
        }
    }

    /**
     * 栏目按钮改动前的原表面：圆角 16dp，选中 = 1dp accent 描边，未选中 = 全透明。
     *
     * 一份实现两处用，这是刻意的：
     * 1. **回退表面**——无背景图 / solid 档 / 墨水屏 / 低内存时它原封不动地留在 `background`
     *    上，观感与改动前逐值一致；
     * 2. **玻璃上方的描边层**——玻璃模式给 `background` 垫
     *    `LayerDrawable(玻璃, 本 Selector)`，选中描边就从这里来（玻璃层本身没有选中态）。
     *
     * 描边为什么不改成别的载体：`View.setForeground` 自 API 23 才有，本项目 minSdk 21；
     * 而 Selector 的 state 分发是 View 对 `background` 的既有能力（改动前选中描边就是靠它
     * 显示的），所以放进 LayerDrawable 后**状态变化完全不需要重建 drawable**。
     */
    private fun createTabBackground(accentColor: Int): Drawable {
        val radius = TAB_CORNER_RADIUS_DP.dpToPx()
        val strokeWidth = TAB_STROKE_WIDTH_DP.dpToPx()

        val selectedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setStroke(strokeWidth.toInt(), accentColor)
        }

        val defaultDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), selectedDrawable)
            addState(intArrayOf(), defaultDrawable)
        }
    }

    /**
     * 把整排栏目按钮的表面重铺到当前档位。
     *
     * 只在签名变化时真的重铺：按钮由 [setupMultiLineTabs] 一次性创建、是常驻 View，
     * 「进设置切玻璃档 / 开关背景图 → 返回本页」不会重建它们，不重铺就停在旧材质上
     * （页面根背景已被 `BaseActivity` 换成实底、按钮却还是玻璃）。
     *
     * 签名取 [wallpaperGlassSignature]（玻璃观感 + 壁纸此刻是否可用），**不用**
     * `GlassSurface.configSignature`：后者不含背景图开关，关背景图后签名不变会漏掉这一次。
     */
    private fun applyTabsGlass() {
        val signature = wallpaperGlassSignature(this)
        if (!tabsGlassRelayoutRequired(lastTabsGlassSignature, signature)) return
        lastTabsGlassSignature = signature
        tabRows.forEach { row ->
            for (i in 0 until row.childCount) {
                (row.getChildAt(i) as? TextView)?.let { applyTabGlass(it, signature) }
            }
        }
    }

    /**
     * 单个栏目按钮的表面。
     *
     * 两条分支，回退逐值等于改动前：
     * - 玻璃拿不到（无背景图 / solid 档 / 墨水屏 / 低内存 / 页面不露壁纸）→ 不做任何事，
     *   保持 [createTabBackground] 的 StateListDrawable 原样（选中描边自有，观感一字不差）；
     * - 玻璃可用 → `background = LayerDrawable(玻璃, 描边层)`：底层采样壁纸，上层是同一个
     *   Selector，选中描边因此仍然存在。
     *
     * **为什么选中态不进 key**：描边层是 StateListDrawable，`isSelected` 变化由 View 把
     * state 分发给 `background`（LayerDrawable 会继续分发给子层），所以点击切换只重绘、
     * 不重建 drawable——省掉每次点击重建一份玻璃 drawable 与重新挂位置跟踪。[signature]
     * 只回答「是不是同一档表面」，同值即零开销返回。
     *
     * 取玻璃用的是 [GlassSurface.pageGlassAllowed] + [GlassSurface.panelBackground]，
     * 与 `io.legado.app.ui.glass.applyWallpaperGlassBackground` 内部同一份判定、同一条管线，
     * 只是后者会直接写 `background`、无法再拼 LayerDrawable，这里少一层封装。
     * `pageGlassAllowed` 已经包含背景图开关（经 `showsWallpaperBehindContent`）、solid 档、
     * 墨水屏、低内存、采样域，因此拿得到玻璃就等于「此刻确实该铺玻璃」。
     */
    private fun applyTabGlass(tabView: TextView, signature: String) {
        // as? String 而非直接比 Any?：让「tag 里存的必须是签名」这件事在代码上显式，
        // 将来有人往里塞别的对象时会立刻失效重铺，而不是静默命中
        if ((tabView.getTag(TAB_GLASS_SIGNATURE_TAG) as? String) == signature) return
        val radiusPx = TAB_CORNER_RADIUS_DP.dpToPx()
        val glass = if (GlassSurface.pageGlassAllowed(this)) {
            GlassSurface.panelBackground(
                host = tabView,
                domain = GlassSamplingDomain.WALLPAPER,
                cornerRadiusPx = radiusPx
            )
        } else {
            null
        }
        if (glass == null) {
            // 回退：旧跟踪必须同时下线，否则位置监听会继续驱动一份不再绘制的 drawable
            GlassSurface.releaseBackgroundTracking(tabView)
            // 当前已经是原表面（新建的 View / 一直没有壁纸）→ 什么都不做，保持
            // createTabBackground 的 StateListDrawable 原样；只有从玻璃退回时才需要重铺一次
            if (tabView.background is LayerDrawable) {
                tabView.background = createTabBackground(accentColor)
            }
        } else {
            tabView.background = LayerDrawable(
                arrayOf(glass, createTabBackground(accentColor))
            )
        }
        // 记在最后：只有真的铺完才认为这一档已完成，中途异常不会把 View 留在「已铺」状态
        tabView.setTag(TAB_GLASS_SIGNATURE_TAG, signature)
    }

    //更新选中状态
    private fun updateTabSelection(position: Int) {
        if (!isDestroyed && !isFinishing) {
            tabRows.forEachIndexed { rowIndex, row ->
                for (i in 0 until row.childCount) {
                    val tabIndex = rowIndex * maxTagsPerRow + i
                    val tabView = row.getChildAt(i) as? TextView
                    val selected = tabIndex == position
                    tabView?.isSelected = selected
                    tabView?.setTextColor(if (selected) accentColor else primaryTextColor)
                }
            }
            // 确保选中标签在视图内
            ensureTabVisible(position)
        }
    }

    private fun ensureTabVisible(position: Int) {
        if (position < 0 || position >= sortList.size) return
        val rowIndex = position / maxTagsPerRow
        if (rowIndex >= tabScrollViews.size) return
        val scrollView = tabScrollViews[rowIndex]
        val rowLayout = tabRows[rowIndex]
        val indexInRow = position % maxTagsPerRow
        if (indexInRow >= rowLayout.childCount) return

        val tabView = rowLayout.getChildAt(indexInRow)
        scrollView.post {
            val tabLeft = tabView.left
            val tabRight = tabView.right
            val scrollViewWidth = scrollView.width
            val padding = 12.dpToPx()
            when {
                tabLeft - padding < scrollView.scrollX ->
                    scrollView.smoothScrollTo(tabLeft - padding, 0)
                tabRight + padding > scrollView.scrollX + scrollViewWidth ->
                    scrollView.smoothScrollTo(tabRight - scrollViewWidth + padding, 0)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 更新当前intent
        shouldFocusSearch = intent.getBooleanExtra("focusSearch", false)
        pureSearch = intent.getBooleanExtra("pureSearch", false)
        // 重新初始化数据，复用时重建
        viewModel.initData(intent) {
            upFragments()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.applyWallpaperGlass()
        shouldFocusSearch = intent.getBooleanExtra("focusSearch", false)
        pureSearch = intent.getBooleanExtra("pureSearch", false)
        binding.viewPager.adapter = adapter
        binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
            }
        })
        viewModel.initData(intent) {
            upFragments()
        }
        onBackPressedDispatcher.addCallback(this) { //监听返回
            if (pureSearch) {
                finish()
                return@addCallback
            }
            if (viewModel.searchKey != null) {
                // 退出搜索
                viewModel.searchKey = null
                upFragments()
                return@addCallback
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // 玻璃档位 / 背景图开关可能在设置页改过：栏目按钮是常驻 View，
        // 不重铺会停在旧材质上。签名未变时是零开销的字符串比较
        applyTabsGlass()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it.shouldHideSoftInput(ev)) {
                    it.hideSoftInput()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // 保存当前选中位置
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("CURRENT_POSITION", binding.viewPager.currentItem)
    }

    // 恢复状态
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val position = savedInstanceState.getInt("CURRENT_POSITION", 0)
        binding.viewPager.currentItem = position
        updateTabSelection(position)
    }

    // 在onDestroy中释放资源
    override fun onDestroy() {
        super.onDestroy()
        fragmentMap.clear()
        tabScrollViews.clear()
        tabRows.clear()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_articles, menu)
        favoriteMenuItem = menu.findItem(R.id.menu_favorite_source)
        if (pureSearch) {
            listOf(
                R.id.menu_login,
                R.id.menu_refresh_sort,
                R.id.menu_set_source_variable,
                R.id.menu_edit_source,
                R.id.menu_clear,
                R.id.menu_switch_layout,
                R.id.menu_read_record
            ).forEach { id ->
                menu.findItem(id)?.isVisible = false
            }
        }
        menu.findItem(R.id.menu_search)?.apply {
            val source = viewModel.rssSource
            val searchUrl = source?.searchUrl ?: return@apply
            val hasSearchUrl = searchUrl.isNotBlank()
            isVisible = hasSearchUrl
            if (hasSearchUrl) {
                (actionView as? SearchView)?.apply {
                    isSubmitButtonEnabled = true
                    queryHint = getString(R.string.rss_search_hint)
                    setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                        override fun onQueryTextSubmit(query: String): Boolean {
                            clearFocus()
                            start(this@RssSortActivity, null, source.sourceUrl, query, pureSearch = pureSearch)
                            return true
                        }

                        override fun onQueryTextChange(newText: String): Boolean {
                            return false
                        }
                    })
                    setOnQueryTextFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            isIconified = true
                        }
                    }
                    if (shouldFocusSearch) {
                        shouldFocusSearch = false
                        post {
                            isIconified = false
                            requestFocus()
                            findFocus()?.let { focusView ->
                                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                    ?.showSoftInput(focusView, InputMethodManager.SHOW_IMPLICIT)
                            }
                        }
                    }
                }
            }
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        if (pureSearch) {
            return super.onMenuOpened(featureId, menu)
        }
        menu.findItem(R.id.menu_login)?.isVisible =
            !viewModel.rssSource?.loginUrl.isNullOrBlank()
        return super.onMenuOpened(featureId, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_login -> startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", viewModel.rssSource?.sourceUrl)
            }

            R.id.menu_refresh_sort -> {
                sortUrls = null
                viewModel.clearSortCache { upFragments() }
            }
            R.id.menu_set_source_variable -> setSourceVariable()
            R.id.menu_edit_source -> viewModel.rssSource?.let {
                editSourceResult.launch {
                    putExtra("sourceUrl", it.sourceUrl)
                }
            }

            R.id.menu_clear -> {
                // 清缓存删掉该源全部已缓存文章且不可恢复，必须有确认
                viewModel.url?.let {
                    alert(R.string.draw) {
                        setMessage(
                            getString(R.string.sure_del) + "\n<" +
                                (viewModel.rssSource?.sourceName ?: "") + ">" +
                                getString(R.string.rss_article_cache)
                        )
                        noButton()
                        yesButton {
                            viewModel.clearArticles()
                            toastOnUi(getString(R.string.rss_article_cache_cleared))
                        }
                    }
                }
            }

            R.id.menu_switch_layout -> {
                viewModel.switchLayout()
                upFragments()
                // 样式切换后没有任何提示，用户不知道当前是第几种排版
                toastOnUi(getString(R.string.rss_article_style_toast, (viewModel.articleStyle ?: 0) + 1))
            }

            R.id.menu_read_record -> showDialogFragment(ReadRecordDialog(viewModel.rssSource?.sourceUrl))

            R.id.menu_share_source -> viewModel.rssSource?.sourceUrl?.let {
                share(it)
            } ?: toastOnUi(R.string.null_url)

            R.id.menu_favorite_source -> favoriteCurrentSource()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun upFragments() {
        lifecycleScope.launch {
            val source = viewModel.rssSource ?: return@launch
            refreshFavoriteMenuState(source.sourceUrl)
            if (viewModel.searchKey != null) {
                sortList.apply {
                    val name = "搜索"
                    val url = source.searchUrl ?: return@apply
                    clear()
                    add(Pair(name, url))
                }
                upFragmentsView()
                return@launch
            }
            if (pureSearch) {
                sortList.clear()
                upFragmentsView()
                return@launch
            }
            viewModel.sortUrl?.takeIf { it.isNotBlank() }?.let { url ->
                val urls: List<Pair<String, String>> = try {
                    if (url.isJsonObject()) {
                        GSONStrict.fromJsonObject<Map<String, String>>(url)
                            .getOrThrow()
                            .map { Pair(it.key, it.value) }
                    } else {
                        listOf(Pair("", url))
                    }
                } catch (_: Exception) {
                    listOf(Pair("", url))
                }
                sortList.apply {
                    clear()
                    addAll(urls)
                }
                upFragmentsView()
                return@launch
            }
            if (sortUrls == null) {
                sortUrls = source.sortUrls()
            }
            sortUrls?.let { urls ->
                sortList.apply {
                    clear()
                    addAll(urls)
                }
                upFragmentsView()
                return@launch
            }
        }
    }
    private fun upFragmentsView() {
        if (pureSearch) {
            binding.titleBar.title = viewModel.searchKey ?: getString(R.string.rss_search_hint)
            binding.tabsContainer.gone()
            adapter.notifyDataSetChanged()
            return
        }
        if (sortList.size == 1) {
            sortList.first().first.takeIf { it.isNotEmpty() }?.let {
                binding.titleBar.title = viewModel.searchKey ?: it
            }
            binding.tabsContainer.gone()
        } else {
            binding.titleBar.title = viewModel.sourceName
            binding.tabsContainer.visible()
            setupMultiLineTabs()
        }
        adapter.notifyDataSetChanged()
        if (sortList.isNotEmpty()) {
            updateTabSelection(binding.viewPager.currentItem)
        }
    }

    private fun setSourceVariable() {
        lifecycleScope.launch {
            val source = viewModel.rssSource
            if (source == null) {
                toastOnUi(appCtx.getString(R.string.ui_main_source_not_found))
                return@launch
            }
            val comment =
                source.getDisplayVariableComment(appCtx.getString(R.string.ui_main_source_variables_can_be_read))
            val variable = withContext(Dispatchers.IO) { source.getVariable() }
            showDialogFragment(
                VariableDialog(
                    getString(R.string.set_source_variable),
                    source.getKey(),
                    variable,
                    comment
                )
            )
        }
    }

    private fun favoriteCurrentSource() {
        val source = viewModel.rssSource ?: return
        val sourceUrl = source.sourceUrl
        lifecycleScope.launch(Dispatchers.IO) {
            val added = if (appDb.rssStarDao.get(sourceUrl, sourceUrl) != null) {
                appDb.rssStarDao.delete(sourceUrl, sourceUrl)
                false
            } else {
                appDb.rssStarDao.insert(
                    RssStar(
                        origin = sourceUrl,
                        sort = "",
                        title = source.sourceName,
                        starTime = System.currentTimeMillis(),
                        link = sourceUrl,
                        type = 0
                    )
                )
                true
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                toastOnUi(if (added) R.string.in_favorites else R.string.out_favorites)
                refreshFavoriteMenuState(sourceUrl)
            }
        }
    }

    /** 按收藏库状态刷新收藏菜单图标 */
    private fun refreshFavoriteMenuState(sourceUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val starred = appDb.rssStarDao.get(sourceUrl, sourceUrl) != null
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                favoriteMenuItem?.setIcon(
                    if (starred) R.drawable.ic_star else R.drawable.ic_star_border
                )
            }
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.rssSource?.setVariable(variable)
    }

    private inner class TabFragmentPageAdapter :
        FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItemPosition(`object`: Any): Int {
            return POSITION_NONE
        }

        override fun getPageTitle(position: Int): CharSequence {
            return sortList[position].first
        }

        override fun getItem(position: Int): Fragment {
            val sort = sortList[position]
            return RssArticlesFragment(sort.first, sort.second, viewModel.searchKey) //获取内容界面
        }

        override fun getCount(): Int {
            return sortList.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val fragment = super.instantiateItem(container, position) as Fragment
            fragmentMap[sortList[position].first] = fragment
            return fragment
        }
    }

    companion object {
        fun start(
            context: Context,
            sortUrl: String?,
            sourceUrl: String,
            key: String? = null,
            focusSearch: Boolean = false,
            pureSearch: Boolean = false
        ) {
            context.startActivity<RssSortActivity> {
                putExtra("sortUrl", sortUrl)
                putExtra("sourceUrl", sourceUrl)
                putExtra("key", key)
                putExtra("focusSearch", focusSearch)
                putExtra("pureSearch", pureSearch)
            }
        }
    }

}
