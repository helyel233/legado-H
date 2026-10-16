package io.legado.app.ui.host

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.main.explore.DiscoverSuiteSession
import io.legado.app.ui.main.explore.DiscoverySuiteManageActivity
import io.legado.app.uikit.v5.components.AppPullRefresh
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-1c「主页」真业务。
 *
 * V5 测试裁决（2026-10-15，主页 tag）：主页「模块」与「套件」功能重合 ——
 * 删除模块功能，保留套件功能；顶栏「管理」直通套件管理页
 * （[DiscoverySuiteManageActivity]，验收裁决不再经二级菜单）。
 *
 * 内容即「发现套件」：数据全走 [DiscoverSuiteSession]
 * （bind/stop 随主页进出挂/解，B13 裁决套件聚合归主页域），
 * 渲染复用套件控件级渲染件（HostHomeSuiteSection），
 * 下拉刷新逐控件重载，指示器接套件加载态。
 */
class HostHomeContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("主页"),
        subtitle = DestTitle.Text("发现套件聚合"),
        actions = listOf(
            ChromeAction(
                id = "manage",
                icon = DestIcon.Vector(HostIcons.Settings),
                contentDescription = DestTitle.Text("管理"),
                onClick = { suiteManageOpen = true },
            ),
        ),
        contentOwnsScroll = true,
    )

    /**
     * 套件管理页开关。实例级状态：宿主 HostScaffold remember 缓存本实例，
     * chrome() 的 onClick 与 Content() 的 LaunchedEffect 共享同一实例，
     * 多开宿主互不干扰、重建不残留（原 companion 静态桥有互顶/复弹问题）。
     */
    private var suiteManageOpen by mutableStateOf(false)

    @Composable
    override fun Content(scope: ContentScope) {
        val context = LocalContext.current

        // 发现套件会话（B13 裁决：套件聚合归主页域）——随主页进出 bind/stop
        DisposableEffect(Unit) {
            DiscoverSuiteSession.bind()
            onDispose { DiscoverSuiteSession.stop() }
        }
        val suiteConfig by DiscoverSuiteSession.config.collectAsStateWithLifecycle()
        val suiteSelectedId by DiscoverSuiteSession
            .selectedSuiteId.collectAsStateWithLifecycle()
        val suiteBooks by DiscoverSuiteSession.widgetBooks.collectAsStateWithLifecycle()
        val suiteRanked by DiscoverSuiteSession
            .rankedWidgetBooks.collectAsStateWithLifecycle()
        val suiteLoading by DiscoverSuiteSession
            .loadingWidgetIds.collectAsStateWithLifecycle()
        val selectedSuite = remember(suiteConfig, suiteSelectedId) {
            suiteConfig.suites.firstOrNull { it.id == suiteSelectedId }
                ?: suiteConfig.suites.firstOrNull()
        }
        val suiteScope = rememberCoroutineScope()
        fun openSuiteBook(book: SearchBook) {
            // 与旧探索页 showBookInfo 同链路：先落搜索历史，再按视频类型兜底跳详情
            suiteScope.launch {
                val isVideo = withContext(Dispatchers.IO) {
                    appDb.searchBookDao.insert(book)
                    SearchBookOpenHelper.isVideoResult(book)
                }
                SearchBookOpenHelper.open(context, book, isVideo)
            }
        }

        /** 空态「去创建」直通套件管理页（常驻管理入口在顶栏「管理」菜单）。 */
        fun openSuiteManage() {
            context.startActivity(
                Intent(context, DiscoverySuiteManageActivity::class.java)
            )
        }

        LaunchedEffect(suiteManageOpen) {
            if (suiteManageOpen) {
                suiteManageOpen = false
                // 验收裁决：齿轮直通套件管理页，不经二级菜单
                context.startActivity(
                    Intent(context, DiscoverySuiteManageActivity::class.java)
                )
            }
        }

        AppPullRefresh(
            refreshing = suiteLoading.isNotEmpty(),
            onRefresh = {
                selectedSuite?.widgets?.forEach { DiscoverSuiteSession.refreshWidget(it) }
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = scope.density.screenPadding,
                    end = scope.density.screenPadding,
                    bottom = 104.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(scope.density.blockGap),
            ) {
                hostHomeSuiteItems(
                    scope = scope,
                    suites = suiteConfig.suites,
                    selectedSuite = selectedSuite,
                    widgetBooks = suiteBooks,
                    rankedWidgetBooks = suiteRanked,
                    loadingWidgetIds = suiteLoading,
                    onSuiteSelect = { DiscoverSuiteSession.selectSuite(it) },
                    onManage = ::openSuiteManage,
                    onOpenBook = ::openSuiteBook,
                )
            }
        }
    }
}
