package io.legado.app.help.config

import android.graphics.Color
import androidx.annotation.RawRes
import androidx.core.graphics.ColorUtils
import io.legado.app.R
import splitties.init.appCtx

/** Loading has its own composition and typography, independent of the book's page template. */
data class EpubLoadingTemplate(
    val id: String,
    val name: String,
    val description: String,
    val scene: Scene,
    val eyebrow: String,
    val caption: String,
    val titleFont: String = "serif",
    val titleSize: Float = 28f,
    val artScale: Float = 1f,
    val day: Palette,
    val night: Palette
) {
    enum class Scene(val key: String, @param:RawRes val artwork: Int) {
        // Retained for existing custom copies and imported loading templates.
        PORTAL("portal", R.raw.epub_loading_portal),
        BOTANICAL("botanical", R.raw.epub_loading_botanical),
        AURORA("aurora", R.raw.epub_loading_aurora)
    }

    data class Palette(
        val background: Int,
        val horizon: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val glow: Int
    ) {
        val darkStatusIcons: Boolean get() = ColorUtils.calculateLuminance(background) > .5
        val darkNavigationIcons: Boolean get() = ColorUtils.calculateLuminance(horizon) > .5
    }

    val builtIn: Boolean get() = id.startsWith("builtin.")
    fun palette(nightMode: Boolean): Palette = if (nightMode) night else day

    companion object {
        private fun colors(vararg hex: String) = hex.map(Color::parseColor).let {
            Palette(it[0], it[1], it[2], it[3], it[4], it[5])
        }

        // Lazy getter: appCtx must already be initialized when the labels are read,
        // so a static initializer must not resolve string resources at class load.
        val builtins: List<EpubLoadingTemplate> get() = listOf(
            EpubLoadingTemplate(
                id = "builtin.loading_botanical",
                name = appCtx.getString(R.string.builtin_epub_loading_botanical),
                description = appCtx.getString(R.string.builtin_epub_loading_botanical_desc),
                scene = Scene.BOTANICAL, eyebrow = "A LETTER IN BLOOM",
                caption = appCtx.getString(R.string.builtin_epub_loading_botanical_caption),
                day = colors("#FBF3E8", "#F3E3DA", "#493D48", "#7E686D", "#B25F74", "#859D87"),
                night = colors("#201B25", "#2E2330", "#E5D5D9", "#BCA1AE", "#D191A2", "#879C8F")
            ),
            EpubLoadingTemplate(
                id = "builtin.loading_aurora",
                name = appCtx.getString(R.string.builtin_epub_loading_aurora),
                description = appCtx.getString(R.string.builtin_epub_loading_aurora_desc),
                scene = Scene.AURORA, eyebrow = "SOMEWHERE BEYOND",
                caption = appCtx.getString(R.string.builtin_epub_loading_aurora_caption),
                titleFont = "sans-serif-light", titleSize = 30f,
                day = colors("#071F2B", "#123D49", "#ECF4E9", "#A4C8C7", "#A5E6CA", "#87A7EB"),
                night = colors("#06151E", "#0D2C35", "#D3E5DC", "#8AAEB0", "#84C2AD", "#7788BD")
            )
        )

        val default: EpubLoadingTemplate get() = builtins.first { it.id == "builtin.loading_botanical" }
    }
}
