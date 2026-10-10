package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.theme.model.BackgroundScene
import io.legado.app.theme.model.BackgroundSource
import io.legado.app.theme.model.BackgroundSpec
import io.legado.app.theme.model.FontRole
import io.legado.app.theme.model.ThemeColorName
import io.legado.app.theme.repository.ThemeRepository
import io.legado.app.uikit.theme.Applicator
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString

/**
 * P0a-1 delegate implementation over the existing theme persistence.
 * All reads hit the original keys, so nothing changes for old data
 * (plan rule 10.0: upgrade from H.1.7.3 keeps every theme setting).
 */
class ThemeRepositoryImpl(context: Context) : ThemeRepository {

    private val appContext = context.applicationContext

    override fun isNightTheme(): Boolean = AppConfig.isNightTheme

    override fun isEInkMode(): Boolean = AppConfig.isEInkMode

    override fun cornerScale(): Float = AppConfig.uiCornerScale

    override fun layoutAlpha(): Int = AppConfig.uiLayoutAlpha

    override fun dialogAlpha(): Int = AppConfig.dialogAlpha

    override fun color(name: ThemeColorName): Int? = with(appContext) {
        when (name) {
            ThemeColorName.PRIMARY -> ThemeStore.primaryColor(this)
            ThemeColorName.ACCENT -> ThemeStore.accentColor(this)
            ThemeColorName.CARD -> themeCardColorOrDefault()
            ThemeColorName.MUTED -> themeMutedColorOrDefault()
            ThemeColorName.TAB_BACKGROUND -> themeTabBackgroundColorOrDefault()
            ThemeColorName.SHELF -> themeShelfColorOrDefault()
            ThemeColorName.BOTTOM_BACKGROUND -> bottomBackground
            ThemeColorName.SEARCH_FIELD -> null // not exposed by palette yet
        }
    }

    override fun fontPath(role: FontRole): String = when (role) {
        FontRole.READING -> ReadBookConfig.textFont
        // A1-5b: package fonts take precedence; legacy keys stay as fallback.
        FontRole.UI -> Applicator.uiFontPath.ifBlank { AppConfig.uiFontPath }
        FontRole.TITLE -> Applicator.titleFontPath.ifBlank { AppConfig.titleFontPath }
    }

    override fun background(scene: BackgroundScene): BackgroundSpec? = with(appContext) {
        val night = AppConfig.isNightTheme
        when (scene) {
            BackgroundScene.MAIN -> {
                val path = getPrefString(if (night) PreferKey.bgImageN else PreferKey.bgImage)
                path?.takeIf { it.isNotBlank() } ?: return@with null
                BackgroundSpec(
                    path = path,
                    crop = getPrefString(if (night) PreferKey.bgImageNCrop else PreferKey.bgImageCrop),
                    blur = getPrefInt(if (night) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring, 0),
                )
            }

            BackgroundScene.BOOK_INFO -> {
                val path = getPrefString(if (night) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage)
                path?.takeIf { it.isNotBlank() }?.let { BackgroundSpec(path = it) }
            }

            BackgroundScene.PANEL -> {
                val path = getPrefString(if (night) PreferKey.panelBgImageN else PreferKey.panelBgImage)
                path?.takeIf { it.isNotBlank() }?.let {
                    BackgroundSpec(
                        path = it,
                        scaleType = getPrefString(
                            if (night) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
                        ),
                    )
                }
            }

            // Reader background delegates to ReadBookConfig (bgStr/bgType,
            // day/night/eink triple) without changing its persistence.
            BackgroundScene.READER -> {
                val config = ReadBookConfig.config
                val bg = config.curBgStr()
                if (bg.isBlank()) {
                    null
                } else {
                    when (config.curBgType()) {
                        0 -> BackgroundSpec(path = bg, source = BackgroundSource.COLOR)
                        1 -> BackgroundSpec(path = bg, source = BackgroundSource.ASSET)
                        else -> BackgroundSpec(path = bg, source = BackgroundSource.FILE)
                    }
                }
            }
        }
    }
}
