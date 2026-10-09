package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.theme.model.BackgroundScene
import io.legado.app.theme.model.BackgroundSpec
import io.legado.app.theme.model.FontRole
import io.legado.app.theme.repository.ThemeWriter
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString

/**
 * P0a-3 write implementation over the existing theme persistence.
 * Single-field writes hit the same ThemeRuntimeKeys/PreferKey keys that
 * ThemeConfig.applyConfig writes, keeping one storage truth.
 */
class ThemeWriterImpl(context: Context) : ThemeWriter {

    private val appContext = context.applicationContext

    override fun setFontPath(role: FontRole, path: String) {
        when (role) {
            FontRole.UI -> AppConfig.uiFontPath = path
            FontRole.TITLE -> AppConfig.titleFontPath = path
            FontRole.READING -> throw UnsupportedOperationException(
                "READING font writes belong to ReadBookConfig until P3"
            )
        }
    }

    override fun setCornerScale(scale: Float) {
        AppConfig.uiCornerScale = scale
    }

    override fun setLayoutAlpha(alpha: Int) {
        AppConfig.uiLayoutAlpha = alpha
    }

    override fun setDialogAlpha(alpha: Int) {
        AppConfig.dialogAlpha = alpha
    }

    override fun setBackground(scene: BackgroundScene, spec: BackgroundSpec?) {
        val night = AppConfig.isNightTheme
        with(appContext) {
            when (scene) {
                BackgroundScene.MAIN -> {
                    putPrefString(
                        if (night) PreferKey.bgImageN else PreferKey.bgImage,
                        spec?.path.orEmpty()
                    )
                    putPrefInt(
                        if (night) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
                        spec?.blur ?: 0
                    )
                    putPrefString(
                        if (night) PreferKey.bgImageNCrop else PreferKey.bgImageCrop,
                        spec?.crop.orEmpty()
                    )
                }

                BackgroundScene.BOOK_INFO -> putPrefString(
                    if (night) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage,
                    spec?.path.orEmpty()
                )

                BackgroundScene.PANEL -> {
                    putPrefString(
                        if (night) PreferKey.panelBgImageN else PreferKey.panelBgImage,
                        spec?.path.orEmpty()
                    )
                    putPrefString(
                        if (night) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType,
                        spec?.scaleType ?: ThemeConfig.PANEL_BG_CROP
                    )
                }

                BackgroundScene.READER -> throw UnsupportedOperationException(
                    "READER background writes belong to ReadBookConfig until P3"
                )
            }
        }
    }
}
