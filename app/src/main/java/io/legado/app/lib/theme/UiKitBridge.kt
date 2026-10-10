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

/**
 * uikit bridge. Registers the theme repository (P0a-1 read contract over the
 * existing persistence; the writer stays until A3) and maps semantic colors
 * for the View side.
 *
 * A1-4: the View side now reads the new engine (Applicator sandwich); the
 * legacy translation ran first at App start (ThemePackageStore.init), so the
 * user's current look carries over.
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
                AppColorName.PRIMARY -> Applicator.resolveColor(PaletteRole.PRIMARY)
                AppColorName.ON_PRIMARY -> Applicator.resolveColor(PaletteRole.ON_PRIMARY)
                AppColorName.SECONDARY -> Applicator.resolveColor(PaletteRole.SECONDARY)
                AppColorName.ON_SECONDARY -> Applicator.resolveColor(PaletteRole.ON_SECONDARY)
                AppColorName.SURFACE -> Applicator.resolveColor(PaletteRole.SURFACE)
                AppColorName.ON_SURFACE -> Applicator.resolveColor(PaletteRole.ON_SURFACE)
                AppColorName.SURFACE_VARIANT -> Applicator.resolveColor(PaletteRole.SURFACE_VARIANT)
                AppColorName.BACKGROUND -> Applicator.resolveColor(PaletteRole.BACKGROUND)
                AppColorName.ON_BACKGROUND -> Applicator.resolveColor(PaletteRole.ON_BACKGROUND)
                AppColorName.OUTLINE -> Applicator.resolveColor(PaletteRole.OUTLINE)
                AppColorName.MUTED -> Applicator.resolveColor(PaletteRole.ON_SURFACE_VARIANT)
                AppColorName.ERROR -> Applicator.resolveColor(PaletteRole.ERROR)
                // Reader page is frozen (plan chapter 1 seam rule).
                AppColorName.READER_TEXT -> Applicator.resolveScheme().readerText.toArgbInt()
                AppColorName.READER_BACKGROUND -> Applicator.resolveScheme().readerBackground.toArgbInt()
            }
            override fun cornerScale(): Float = repository.cornerScale()
        }
    }

    private fun Color.toArgbInt(): Int = toArgb()
}
