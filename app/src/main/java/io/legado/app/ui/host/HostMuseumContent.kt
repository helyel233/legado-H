package io.legado.app.ui.host

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.uikit.v5.components.AppBadge
import androidx.compose.foundation.layout.width
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppCover
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppDivider
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppError
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppGridItem
import io.legado.app.uikit.v5.components.AppGroup
import io.legado.app.uikit.v5.components.AppIconBox
import io.legado.app.uikit.v5.components.AppIcons
import io.legado.app.uikit.v5.components.ImageVectorHolder
import io.legado.app.uikit.v5.components.AppListItem
import io.legado.app.uikit.v5.components.AppLoading
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppPanel
import io.legado.app.uikit.v5.components.AppProgressBar
import io.legado.app.uikit.v5.components.AppSearchField
import io.legado.app.uikit.v5.components.AppSelectableRow
import io.legado.app.uikit.v5.components.AppSkeleton
import io.legado.app.uikit.v5.components.AppSkeletonList
import io.legado.app.uikit.v5.components.AppSlider
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.contract.AppContent
import io.legado.app.uikit.v5.contract.ContentScope
import io.legado.app.uikit.v5.contract.DestIcon
import io.legado.app.uikit.v5.contract.DestTitle
import io.legado.app.uikit.v5.contract.ScreenChrome
import io.legado.app.uikit.v5.contract.TopBarMode
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.uikit.v5.token.ShapeKind

/**
 * P2-6 组件博物馆 —— 全部新零件的验收页。
 *
 * 这个页面本身就是"页面怎么写"的活规范：
 * 它只调零件、只传语义参数，一个颜色值 / 一个圆角数字都没写。
 */
class HostMuseumContent : AppContent {

    override fun chrome(): ScreenChrome = ScreenChrome(
        topBar = TopBarMode.LARGE_TITLE,
        title = DestTitle.Text("组件博物馆"),
        subtitle = DestTitle.Text("全部消费全局参数 · 页面零外观代码"),
        actions = listOf(
            io.legado.app.uikit.v5.contract.ChromeAction(
                id = "menu",
                icon = DestIcon.Vector(HostIcons.Menu),
                contentDescription = DestTitle.Text("菜单"),
                onClick = {},
            ),
        ),
    )

    @Composable
    override fun Content(scope: ContentScope) {
        var search by remember { mutableStateOf("") }
        var sw by remember { mutableStateOf(true) }
        var radio by remember { mutableStateOf(1) }
        var slider by remember { mutableStateOf(0.62f) }
        var chip by remember { mutableStateOf(0) }
        var dialog by remember { mutableStateOf(false) }
        var sheet by remember { mutableStateOf(false) }
        val d = scope.density

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenPadding),
            verticalArrangement = Arrangement.spacedBy(d.blockGap),
        ) {

            // ---- 材质四档 ----
            AppGroup(title = "材质四档（E0 平铺 / E1 嵌入 / E2 浮起 / E3 悬浮）") {
                Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                    listOf(
                        MaterialLevel.EMBEDDED to "E1",
                        MaterialLevel.RAISED to "E2",
                        MaterialLevel.FLOATING to "E3",
                    ).forEach { (lv, name) ->
                        io.legado.app.uikit.v5.runtime.AppSurface(
                            level = lv,
                            modifier = Modifier.weight(1f).height(64.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                androidx.compose.material3.Text(
                                    text = name,
                                    style = scope.type.label,
                                    color = scope.color.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            // ---- 卡片与分组 ----
            AppGroup(title = "卡片与分组") {
                AppCard(onClick = {}) {
                    Column(Modifier.padding(d.cardPadding)) {
                        androidx.compose.material3.Text("可点卡片", style = scope.type.title, color = scope.color.onSurface)
                        androidx.compose.material3.Text(
                            "按压有微缩放，形状跟随形状族",
                            style = scope.type.caption,
                            color = scope.color.onSurfaceVariant,
                        )
                    }
                }
                AppPanel {
                    Column(Modifier.padding(d.cardPadding)) {
                        androidx.compose.material3.Text("面板（不可点）", style = scope.type.label, color = scope.color.onSurface)
                        Spacer(Modifier.height(d.blockGap / 2))
                        AppProgressBar(0.62f)
                    }
                }
            }

            // ---- 列表 ----
            AppGroup(title = "列表") {
                AppCard { 
                    Column {
                        AppListItem(
                            headline = "双行列表项",
                            supporting = "辅助说明一行",
                            card = false,
                            leading = { AppIconBox(ImageVectorHolder(HostIcons.Stats)) },
                            trailing = { AppBadge(3) },
                        )
                        AppDivider()
                        AppListItem(
                            headline = "带 overline 的项",
                            overline = "OVERLINE",
                            supporting = "第三行文本",
                            card = false,
                            leading = { AppIconBox(ImageVectorHolder(HostIcons.Rss)) },
                            trailing = { AppGhostButton("打开") {} },
                        )
                        AppDivider()
                        AppListItem(headline = "单行紧凑项", dense = true, card = false)
                    }
                }
            }

            // ---- 网格 ----
            AppGroup(title = "网格（书架封面卡同一零件）") {
                Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                    AppGridItem(
                        headline = "雪中悍刀行",
                        supporting = "62%",
                        media = { AppCover(label = "雪", progress = 0.62f, modifier = Modifier.fillMaxWidth().height(104.dp)) },
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    )
                    AppGridItem(
                        headline = "三体",
                        supporting = "已读完",
                        media = { AppCover(label = "三", progress = 1f, done = true, updateDot = true, modifier = Modifier.fillMaxWidth().height(104.dp)) },
                        modifier = Modifier.weight(1f),
                        onClick = {},
                    )
                }
            }

            // ---- 输入 ----
            AppGroup(title = "输入") {
                AppSearchField(value = search, onValueChange = { search = it }, placeholder = "搜索书名或作者")
                AppCard {
                    Column(Modifier.padding(d.cardPadding), verticalArrangement = Arrangement.spacedBy(d.blockGap)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text("开关", style = scope.type.body, color = scope.color.onSurface, modifier = Modifier.weight(1f))
                            AppSwitch(checked = sw, onCheckedChange = { sw = it })
                        }
                        AppDivider()
                        AppSelectableRow("单选 · 档位一", radio == 0, onClick = { radio = 0 })
                        AppSelectableRow("单选 · 档位二", radio == 1, onClick = { radio = 1 })
                        AppSelectableRow("多选 · 附加项", radio == 2, multi = true, onClick = { radio = if (radio == 2) 1 else 2 })
                        AppDivider()
                        androidx.compose.material3.Text(
                            "滑块 ${"%.2f".format(slider)}",
                            style = scope.type.caption,
                            color = scope.color.onSurfaceVariant,
                        )
                        AppSlider(value = slider, onValueChange = { slider = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                            listOf("全部", "小说", "科技").forEachIndexed { i, t ->
                                AppChip(text = t, selected = chip == i, onClick = { chip = i })
                            }
                        }
                    }
                }
            }

            // ---- 状态 ----
            AppGroup(title = "状态（骨架屏 / 空 / 错误）") {
                AppCard {
                    Column(Modifier.padding(d.cardPadding), verticalArrangement = Arrangement.spacedBy(d.blockGap / 2)) {
                        AppSkeleton(Modifier.fillMaxWidth().height(12.dp))
                        AppSkeleton(Modifier.fillMaxWidth(0.6f).height(12.dp))
                        AppSkeletonList(rows = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap), verticalAlignment = Alignment.CenterVertically) {
                            AppLoading()
                            androidx.compose.material3.Text("不确定加载", style = scope.type.caption, color = scope.color.onSurfaceVariant)
                        }
                    }
                }
                AppEmpty(
                    title = "书架还是空的",
                    supporting = "导入本地书籍，或者去发现页找找看",
                    illustration = { MuseumIllustration(scope) },
                    actionLabel = "导入书籍",
                    onAction = { sheet = true },
                )
                AppError(
                    title = "书源连接失败",
                    supporting = "精选源 A 响应超时（5s）",
                    onRetry = { sheet = true },
                )
            }

            // ---- 弹层 ----
            AppGroup(title = "弹层（同一套材质，形状族统一）") {
                Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                    AppFilledButton("对话框") { dialog = true }
                    AppGhostButton("底部抽屉") { sheet = true }
                }
            }

            Spacer(Modifier.height(d.screenPadding))
        }

        if (dialog) {
            AppDialog(onDismiss = { dialog = false }) {
                Column(Modifier.padding(d.screenPadding * 1.2f), verticalArrangement = Arrangement.spacedBy(d.blockGap)) {
                    androidx.compose.material3.Text("对话框", style = scope.type.title, color = scope.color.onSurface)
                    androidx.compose.material3.Text(
                        "弹窗、抽屉、菜单与其它容器共用同一套玻璃与形状族。",
                        style = scope.type.body,
                        color = scope.color.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(d.blockGap)) {
                        AppFilledButton("确认") { dialog = false }
                        AppGhostButton("取消") { dialog = false }
                    }
                }
            }
        }
        if (sheet) {
            AppMenuSheet(
                title = "选择操作",
                actions = listOf(
                    "导入本地书籍" to { },
                    "从书源搜索" to { },
                    "扫描文件夹" to { },
                ),
                onDismiss = { sheet = false },
            )
        }
    }
}

/** 空态插画：主题色自绘（书 + 虚位），页面不引外部图。 */
@Composable
private fun MuseumIllustration(scope: ContentScope) {
    val c = scope.color
    Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(96.dp)) {
            val w = size.width
            val h = size.height
            // 后面的书
            drawRoundRect(
                color = c.onSurface.copy(alpha = 0.10f),
                topLeft = Offset(w * 0.12f, h * 0.20f),
                size = Size(w * 0.52f, h * 0.62f),
                cornerRadius = CornerRadius(w * 0.07f),
            )
            // 前面的书
            drawRoundRect(
                color = c.primary.copy(alpha = 0.16f),
                topLeft = Offset(w * 0.30f, h * 0.12f),
                size = Size(w * 0.56f, h * 0.68f),
                cornerRadius = CornerRadius(w * 0.07f),
            )
            drawRoundRect(
                color = c.primary.copy(alpha = 0.45f),
                topLeft = Offset(w * 0.34f, h * 0.30f),
                size = Size(w * 0.34f, h * 0.045f),
                cornerRadius = CornerRadius(h * 0.02f),
            )
            drawRoundRect(
                color = c.primary.copy(alpha = 0.30f),
                topLeft = Offset(w * 0.34f, h * 0.42f),
                size = Size(w * 0.24f, h * 0.04f),
                cornerRadius = CornerRadius(h * 0.02f),
            )
        }
    }
}
