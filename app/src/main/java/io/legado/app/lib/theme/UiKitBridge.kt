package io.legado.app.lib.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.legado.app.theme.repository.ThemeRepositoryHost
import io.legado.app.theme.repository.ThemeWriterHost
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.theme.AppColorName
import io.legado.app.uikit.theme.AppColorProvider
import io.legado.app.uikit.theme.Applicator
import io.legado.app.uikit.theme.AppUiColors
import io.legado.app.theme.palette.PaletteRole
import io.legado.app.uikit.v5.resolve.ThemeResolver

/**
 * uikit bridge. Registers the theme repository (P0a-1 read contract over the
 * existing persistence; the writer stays until A3) and maps semantic colors
 * for the View side.
 *
 * P4-0 切桥：13 路语义色改读 **V5 ThemeResolver**（V5 包库成为唯一真相，
 * 未搬迁旧页面的颜色随新主题包实时变化）。READER 两路保持旧 Applicator ——
 * 读书界面冻结（seam rule），颜色不受 V5 包影响。
 */
object UiKitBridge {

    fun init(context: Context) {
        val app = context.applicationContext
        AppToast.init(app)
        val repository = ThemeRepositoryImpl(app)
        ThemeRepositoryHost.register(repository)
        ThemeWriterHost.register(ThemeWriterImpl(app))
        AppUiColors.provider = object : AppColorProvider {
            override fun color(name: AppColorName): Int = when (name) {
                AppColorName.PRIMARY -> ThemeResolver.resolveColor(PaletteRole.PRIMARY)
                AppColorName.ON_PRIMARY -> ThemeResolver.resolveColor(PaletteRole.ON_PRIMARY)
                AppColorName.SECONDARY -> ThemeResolver.resolveColor(PaletteRole.TERTIARY)
                AppColorName.ON_SECONDARY -> ThemeResolver.resolveColor(PaletteRole.ON_TERTIARY)
                AppColorName.SURFACE -> ThemeResolver.resolveColor(PaletteRole.SURFACE)
                AppColorName.ON_SURFACE -> ThemeResolver.resolveColor(PaletteRole.ON_SURFACE)
                AppColorName.SURFACE_VARIANT -> ThemeResolver.resolveColor(PaletteRole.SURFACE_VARIANT)
                AppColorName.BACKGROUND -> ThemeResolver.resolveColor(PaletteRole.BACKGROUND)
                AppColorName.ON_BACKGROUND -> ThemeResolver.resolveColor(PaletteRole.ON_BACKGROUND)
                AppColorName.OUTLINE -> ThemeResolver.resolveColor(PaletteRole.OUTLINE_VARIANT)
                AppColorName.MUTED -> ThemeResolver.resolveColor(PaletteRole.ON_SURFACE_VARIANT)
                AppColorName.ERROR -> ThemeResolver.resolveColor(PaletteRole.ERROR)
                // Reader page is frozen (plan chapter 1 seam rule): keep the legacy engine.
                AppColorName.READER_TEXT -> Applicator.resolveScheme().readerText.toArgbInt()
                AppColorName.READER_BACKGROUND -> Applicator.resolveScheme().readerBackground.toArgbInt()
            }
            override fun cornerScale(): Float = io.legado.app.uikit.layout.LayoutEngine.radiusScale
        }
    }

    private fun Color.toArgbInt(): Int = toArgb()
}
