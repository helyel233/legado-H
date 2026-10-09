package io.legado.app.theme.repository

import io.legado.app.theme.model.BackgroundScene
import io.legado.app.theme.model.BackgroundSpec
import io.legado.app.theme.model.FontRole

/**
 * Write-side contract of the theme package (docs/ui-rewrite-plan.md 5.2).
 *
 * Single-field writes only: the full "apply a theme package" flow stays
 * behind ThemeConfig.applyConfig in the app until P0a-4 moves it here.
 * All writes target the same underlying keys as before, so values written
 * by H.1.7.3 remain readable and vice versa (plan rule 10.0).
 */
interface ThemeWriter {

    /** Blank path clears the custom font for the role. */
    fun setFontPath(role: FontRole, path: String)

    fun setCornerScale(scale: Float)

    fun setLayoutAlpha(alpha: Int)

    fun setDialogAlpha(alpha: Int)

    /**
     * Write one background slot. Pass null to clear the slot.
     * READER writes are owned by ReadBookConfig until P3 and throw
     * [UnsupportedOperationException] here.
     */
    fun setBackground(scene: BackgroundScene, spec: BackgroundSpec?)
}

/**
 * Holder for the registered implementation. The app registers at startup.
 */
object ThemeWriterHost {

    @Volatile
    private var writer: ThemeWriter? = null

    fun register(writer: ThemeWriter) {
        this.writer = writer
    }

    fun get(): ThemeWriter =
        writer ?: throw IllegalStateException("ThemeWriter not registered")
}

