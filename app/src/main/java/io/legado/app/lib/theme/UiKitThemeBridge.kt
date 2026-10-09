package io.legado.app.lib.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import io.legado.app.help.config.AppConfig
import io.legado.app.theme.model.ThemeColorName
import io.legado.app.theme.repository.ThemeRepositoryHost
import io.legado.app.uikit.theme.AppColorScheme
import io.legado.app.uikit.theme.darkAppColorScheme
import io.legado.app.uikit.theme.lightAppColorScheme

/**
 * Maps the current theme persistence onto the uikit semantic scheme so
 * Compose screens can render with the user's active theme (P2-a).
 */
object UiKitThemeBridge {

    fun colorScheme(context: Context): AppColorScheme {
        val repo = ThemeRepositoryHost.get()
        val dark = AppConfig.isNightTheme
        val base = if (dark) darkAppColorScheme() else lightAppColorScheme()
        return base.copy(
            primary = map(repo.color(ThemeColorName.PRIMARY), base.primary),
            secondary = map(repo.color(ThemeColorName.ACCENT), base.secondary),
            surface = map(repo.color(ThemeColorName.CARD), base.surface),
            muted = map(repo.color(ThemeColorName.MUTED), base.muted),
            background = map(repo.color(ThemeColorName.BOTTOM_BACKGROUND), base.background),
        )
    }

    private fun map(colorInt: Int?, fallback: Color): Color =
        colorInt?.let { Color(it) } ?: fallback
}
