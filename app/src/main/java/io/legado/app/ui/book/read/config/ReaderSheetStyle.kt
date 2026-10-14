package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.toColorInt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.UiCorner
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

object ReaderSheetStyle {

    data class Palette(
        val surface: Int,
        val panel: Int,
        val panelStrong: Int,
        val stroke: Int,
        val textColor: Int,
        val secondaryTextColor: Int,
        val primaryColor: Int,
        val accentColor: Int
    )

    fun resolve(context: Context, baseColor: Int = context.bottomBackground): Palette {
        val base = baseColor
        val isLight = ColorUtils.isColorLight(base)
        val textColor = context.primaryTextColor
        val accentColor = context.accentColor
        val primaryColor = context.primaryColor
        val cardColor = context.themeCardColorOrDefault()
        val surfaceBase = ColorUtils.blendColors(base, cardColor, if (isLight) 0.86f else 0.64f)
        val surface = ColorUtils.blendColors(
            surfaceBase,
            primaryColor,
            if (isLight) 0.08f else 0.16f
        )
        val panel = ColorUtils.blendColors(
            surface,
            accentColor,
            if (isLight) 0.06f else 0.12f
        )
        val panelStrong = ColorUtils.blendColors(
            surface,
            primaryColor,
            if (isLight) 0.14f else 0.22f
        )
        val strokeBase = ColorUtils.blendColors(primaryColor, textColor, 0.3f)
        val stroke = ColorUtils.adjustAlpha(strokeBase, if (isLight) 0.2f else 0.28f)
        return Palette(
            surface = surface,
            panel = panel,
            panelStrong = panelStrong,
            stroke = stroke,
            textColor = textColor,
            secondaryTextColor = context.secondaryTextColor,
            primaryColor = primaryColor,
            accentColor = accentColor
        )
    }

    fun topSheetDrawable(palette: Palette, radiusDp: Float = 10f): GradientDrawable {
        val radius = UiCorner.scaledDp(radiusDp)
        return GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f
            )
            setColor(palette.surface)
            setStroke(1.dpToPx(), palette.stroke)
        }
    }

    fun blockDrawable(fillColor: Int, strokeColor: Int, radiusDp: Float = 10f): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = UiCorner.scaledDp(radiusDp)
            setColor(fillColor)
            setStroke(1.dpToPx(), strokeColor)
        }
    }
}

/**
 * 阅读页底部面板取色（V5 迁移期共享入口）：**跟随阅读页当前背景色**，这是功能特性，勿改成固定主题色。
 *
 * 与旧 `ReaderComposeComponents.rememberReaderMenuDialogStyle()` 同源同逻辑：
 * `AppConfig.readBarStyleFollowPage` 且当前背景为纯色时取 `ReadBookConfig.durConfig.curBgStr()`，
 * 否则退回 `context.bottomBackground`，再交给 [ReaderSheetStyle.resolve] 解析出面板配色。
 * 迁移期由 `ui/book/read/config` 域各面板共用（共享容器签名被 11 个文件消费，暂不改动它）。
 */
@Composable
internal fun rememberReaderPagePalette(): ReaderSheetStyle.Palette {
    val context = LocalContext.current
    val bgColor = if (AppConfig.readBarStyleFollowPage && ReadBookConfig.durConfig.curBgType() == 0) {
        runCatching {
            ReadBookConfig.durConfig.curBgStr().toColorInt()
        }.getOrDefault(context.bottomBackground)
    } else {
        context.bottomBackground
    }
    return remember(bgColor) { ReaderSheetStyle.resolve(context, bgColor) }
}
