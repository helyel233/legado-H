package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.uikit.theme.AppColorScheme
import io.legado.app.uikit.theme.Applicator

/**
 * A1-4: Compose screens now read the new engine (Applicator sandwich:
 * factory defaults / theme package / local tweaks). The function signature
 * is kept for the existing call sites.
 */
object UiKitThemeBridge {

    fun colorScheme(context: Context): AppColorScheme = Applicator.resolveScheme()
}
