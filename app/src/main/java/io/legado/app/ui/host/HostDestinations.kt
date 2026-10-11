package io.legado.app.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.AppDestination
import io.legado.app.uikit.v5.contract.ChromeAction
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.SideGroup
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.registry.DestinationRegistry
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P1-2 / P1-5 占位注册表。
 *
 * 6 个 Tab 在这里登记 —— 这就是"一张表"的样子。P4 搬迁时，
 * 每个占位内容会被真正的业务 Content 替换，注册表结构一行不改。
 *
 * 现状对照：`MainBottomNavConfig.specs` 6 项、默认可见 3 项（书架/发现/我的），
 * 这里用 `defaultVisible` 表达同一件事。
 */
object HostDestinations {

    fun registry(): DestinationRegistry = DestinationRegistry(
        listOf(
            AppDestination(
                id = "shelf", title = DestTitle.Text("书架"),
                icon = DestIcon.Vector(HostIcons.Shelf),
                factory = { PlaceholderContent("书架", "共 128 本 · 在读 6 本", "shelf") },
                topLevel = true, lockedVisible = true,
                sideGroup = SideGroup.MAIN, order = 0,
            ),
            AppDestination(
                id = "home", title = DestTitle.Text("主页"),
                icon = DestIcon.Vector(HostIcons.Home),
                factory = { PlaceholderContent("主页", "今天读了 42 分钟", "home") },
                topLevel = true, defaultVisible = true,
                sideGroup = SideGroup.MAIN, order = 1,
            ),
            AppDestination(
                id = "explore", title = DestTitle.Text("发现"),
                icon = DestIcon.Vector(HostIcons.Explore),
                factory = { PlaceholderContent("发现", "已启用 24 个书源", "explore") },
                topLevel = true,
                sideGroup = SideGroup.MAIN, order = 2,
            ),
            AppDestination(
                id = "rss", title = DestTitle.Text("订阅"),
                icon = DestIcon.Vector(HostIcons.Rss),
                factory = { PlaceholderContent("订阅", "12 个源 · 19 篇未读", "rss") },
                topLevel = true, defaultVisible = true,
                sideGroup = SideGroup.MAIN, order = 3,
            ),
            AppDestination(
                id = "stats", title = DestTitle.Text("统计"),
                icon = DestIcon.Vector(HostIcons.Stats),
                factory = { PlaceholderContent("统计", "2026 年 10 月", "stats") },
                topLevel = true, defaultVisible = true,
                sideGroup = SideGroup.MAIN, order = 4,
            ),
            AppDestination(
                id = "my", title = DestTitle.Text("我的"),
                icon = DestIcon.Vector(HostIcons.My),
                factory = { PlaceholderContent("我的", "藏书 128 · 连续 47 天", "my") },
                topLevel = true, lockedVisible = true,
                sideGroup = SideGroup.MAIN, order = 5,
            ),
            // 非 Tab，只进侧栏：验证"注册表同时驱动底栏和侧栏"
            AppDestination(
                id = "appearance", title = DestTitle.Text("UI 设置"),
                icon = DestIcon.Vector(HostIcons.Settings),
                factory = { PlaceholderContent("UI 设置", "主题与界面管理中心（P3 落地）", "appearance") },
                topLevel = false,
                sideGroup = SideGroup.SETTINGS, order = 10,
            ),
            // P2-6 组件博物馆：全部新零件的验收页
            AppDestination(
                id = "museum", title = DestTitle.Text("组件博物馆"),
                icon = DestIcon.Vector(HostIcons.Check),
                factory = { HostMuseumContent() },
                topLevel = false,
                sideGroup = SideGroup.CONTENT, order = 20,
            ),
        )
    )
}

/**
 * 占位内容 —— 同时充当"页面怎么写"的示范。
 *
 * 注意它拿到的只有 [ContentScope]：说"我要卡片形状"、说"用浮起档"，
 * 碰不到 VisualSpec、碰不到顶栏底栏、碰不到任何颜色数值。
 */
private class PlaceholderContent(
    private val label: String,
    private val desc: String,
    private val id: String,
) : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text(label),
        subtitle = DestTitle.Text(desc),
        showBack = false,
        actions = listOf(
            ChromeAction(
                id = "search",
                icon = DestIcon.Vector(HostIcons.Search),
                contentDescription = DestTitle.Text("搜索"),
                onClick = {},
            ),
        ),
    )

    @Composable
    override fun Content(scope: ContentScope) {
        val density = scope.density
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap),
        ) {
            Text(
                text = "占位页：$id",
                style = scope.type.title,
                color = scope.color.onSurfaceVariant,
            )
            Text(
                text = "业务内容在 P4 逐页搬进来。这一页用来验证骨架：外框、材质、形状、密度全部来自全局参数。",
                style = scope.type.body,
                color = scope.color.onSurfaceVariant,
            )

            // 材质四档对照
            MaterialLevel.entries.forEach { level ->
                scope.Surface(
                    level = level,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(density.cardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .padding(end = 8.dp),
                        ) {
                            Icon(
                                painter = rememberVectorPainter(HostIcons.Check),
                                contentDescription = null,
                                tint = scope.color.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "材质 ${level.id}",
                                style = scope.type.label,
                                color = scope.color.onSurface,
                            )
                            Text(
                                text = level.name,
                                style = scope.type.caption,
                                color = scope.color.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 形状族对照（页面只能按"种类"取形状）
            Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap)) {
                listOf(ShapeKind.TAG, ShapeKind.CARD, ShapeKind.COVER).forEach { kind ->
                    scope.Surface(
                        level = MaterialLevel.RAISED,
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = kind.name,
                                style = scope.type.caption,
                                color = scope.color.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(density.blockGap))
            Text(
                text = "滚动试试：顶栏会折叠、底栏会按界面包的可见性设置响应。",
                style = scope.type.caption,
                color = scope.color.onSurfaceVariant,
            )
        }
    }
}
