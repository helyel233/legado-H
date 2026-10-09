package io.legado.app.theme.repository

import io.legado.app.theme.model.BackgroundScene
import io.legado.app.theme.model.BackgroundSpec
import io.legado.app.theme.model.FontRole
import io.legado.app.theme.model.ThemeColorName

/**
 * Read-side contract of the theme package (docs/ui-rewrite-plan.md 5.2).
 *
 * P0a-1: the app registers a delegate implementation over the existing
 * persistence (PreferKey SP / app_themes.xml / themeConfig.json /
 * externalFiles/themePackages). Old keys stay untouched, so upgrading from
 * any previous release keeps all theme settings (plan rule 10.0).
 * P0a-2 will move the 4 persistence layers behind this interface only.
 */
interface ThemeRepository {

    fun isNightTheme(): Boolean

    fun isEInkMode(): Boolean

    /** Global corner scale, user setting uiCornerScale (0..3, default 1). */
    fun cornerScale(): Float

    fun layoutAlpha(): Int

    fun dialogAlpha(): Int

    fun color(name: ThemeColorName): Int?

    fun fontPath(role: FontRole): String

    /** Read view over background keys; null when the scene has no image set. */
    fun background(scene: BackgroundScene): BackgroundSpec?
}

/**
 * Holder for the registered implementation. The app registers at startup.
 */
object ThemeRepositoryHost {

    @Volatile
    private var repository: ThemeRepository? = null

    fun register(repo: ThemeRepository) {
        repository = repo
    }

    fun get(): ThemeRepository =
        repository ?: throw IllegalStateException("ThemeRepository not registered")
}
