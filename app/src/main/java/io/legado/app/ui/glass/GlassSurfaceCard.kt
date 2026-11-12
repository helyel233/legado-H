package io.legado.app.ui.glass

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.LegadoMiuixCard

/**
 * 卡片级玻璃壳：页面内卡片与弹窗面板共用的单一入口。
 *
 * 命中玻璃时用 [GlassPanel] 垫底、卡片填充让位为透明；未命中时原样铺 [fillColor]
 * 的实底卡片。观感参数仍由底层管线单点决定，本组件只声明采样方式：
 * - [screenAligned] = false：页面内卡片，按宿主根视图对齐（[GlassSurface.pageGlassAllowed]）；
 * - [screenAligned] = true：独立窗口（对话框），按屏幕对齐（[GlassSurface.dialogGlassAllowed]）。
 *
 * 约束：长列表的通用行卡片走
 * [io.legado.app.ui.widget.compose.AppManagementCard] 的 `glassSurface` 分支（同一份
 * [GlassPanel] 管线与实底回退）；本组件只给自带布局的少数量卡片与对话框面板，
 * 避免每个页面各写一份「玻璃壳 + 回退」。
 *
 * **可用性走 [rememberGlassAvailability]（可观察 State）**：它读的是「背景图开关 + 宿主
 * 页面是否露壁纸」，而 `configSignature` 只含档位 / 强度 / 描边 / 夜间。把结论包进
 * `remember(context, screenAligned, configSignature)` 会让「开背景图 + 玻璃 → 关背景图
 * → 返回」命中旧值，卡片停在玻璃上（与 [GlassPanel] 内部同一条纪律）。
 */
@Composable
fun GlassSurfaceCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    fillColor: Color,
    contentColor: Color,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    screenAligned: Boolean = false,
    configSignature: String = rememberGlassConfigSignature(),
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    // 壁纸可用性走可观察入口：背景图开关不进 configSignature（类注释），只靠父级重组
    // 会命中旧结论，卡片停在玻璃上 / 玻璃不出现。
    // screenAligned 换的是整套判定入口（对话框按屏幕对齐 / 页面按根视图），必须进 key：
    // 不传的话闭包会捕获旧值，effect 也不重建（与 rememberGlassAvailability 的签名契约一致）。
    val glassEnabled = rememberGlassAvailability(screenAligned) {
        if (screenAligned) {
            GlassSurface.dialogGlassAllowed(context)
        } else {
            GlassSurface.pageGlassAllowed(context)
        }
    }
    if (!glassEnabled) {
        LegadoMiuixCard(
            modifier = modifier,
            color = fillColor,
            contentColor = contentColor,
            cornerRadius = cornerRadius,
            insidePadding = insidePadding,
            content = content
        )
        return
    }
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    GlassPanel(
        domain = GlassSamplingDomain.WALLPAPER,
        cornerRadiusPx = radiusPx,
        configSignature = configSignature,
        screenAligned = screenAligned,
        modifier = modifier.clip(RoundedCornerShape(cornerRadius))
    ) {
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
            contentColor = contentColor,
            cornerRadius = cornerRadius,
            insidePadding = insidePadding,
            content = content
        )
    }
}
