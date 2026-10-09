package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.help.config.AppConfig
import io.legado.app.theme.model.ThemeColorName
import io.legado.app.theme.repository.ThemeRepository
import io.legado.app.theme.repository.ThemeRepositoryHost
import io.legado.app.theme.repository.ThemeWriterHost
import io.legado.app.uikit.facade.AppToast
import io.legado.app.uikit.theme.AppColorName
import io.legado.app.uikit.theme.AppColorProvider
import io.legado.app.uikit.theme.AppUiColors
import io.legado.app.utils.ColorUtils

/**
 * uikit bridge. Registers the theme repository (P0a-1 read contract over the
 * existing persistence) and maps semantic colors for the View side.
 * After P0a-2 the repository implementation moves the persistence layers
 * behind modules/theme; this file's mapping stays stable.
 */
object UiKitBridge {

    fun init(context: Context) {
        val app = context.applicationContext
        AppToast.init(app)
        val repository = ThemeRepositoryImpl(app)
        ThemeRepositoryHost.register(repository)
        ThemeWriterHost.register(ThemeWriterImpl(app))
        AppUiColors.provider = object : AppColorProvider {
            override fun color(name: AppColorName): Int = mapColor(app, repository, name)
            override fun cornerScale(): Float = repository.cornerScale()
        }
    }

    private fun mapColor(context: Context, repo: ThemeRepository, name: AppColorName): Int =
        with(context) {
            val dark = repo.isNightTheme()
            when (name) {
                AppColorName.PRIMARY -> repo.color(ThemeColorName.PRIMARY) ?: primaryColor
                AppColorName.ON_PRIMARY -> {
                    val c = repo.color(ThemeColorName.PRIMARY) ?: primaryColor
                    if (ColorUtils.isColorLight(c)) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
                }
                AppColorName.SECONDARY -> repo.color(ThemeColorName.ACCENT) ?: accentColor
                AppColorName.ON_SECONDARY -> {
                    val c = repo.color(ThemeColorName.ACCENT) ?: accentColor
                    if (ColorUtils.isColorLight(c)) 0xFF1C1B1F.toInt() else 0xFFFFFFFF.toInt()
                }
                AppColorName.SURFACE -> repo.color(ThemeColorName.CARD) ?: themeCardColorOrDefault()
                AppColorName.ON_SURFACE -> getPrimaryTextColor(dark)
                AppColorName.SURFACE_VARIANT -> repo.color(ThemeColorName.CARD) ?: themeCardColorOrDefault()
                AppColorName.BACKGROUND -> backgroundColor
                AppColorName.ON_BACKGROUND -> getPrimaryTextColor(dark)
                AppColorName.OUTLINE -> themeDividerColorOrDefault()
                AppColorName.MUTED -> repo.color(ThemeColorName.MUTED) ?: themeMutedColorOrDefault()
                AppColorName.ERROR -> 0xFFB3261E.toInt()
                AppColorName.READER_TEXT -> getPrimaryTextColor(dark)
                AppColorName.READER_BACKGROUND -> backgroundColor
            }
        }
}
