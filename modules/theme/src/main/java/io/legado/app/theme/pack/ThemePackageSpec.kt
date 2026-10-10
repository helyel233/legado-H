package io.legado.app.theme.pack

import com.google.gson.Gson
import io.legado.app.theme.palette.PaletteStrategy

/**
 * A1-2 theme package v1 data layer (docs/ui-rewrite-plan-v4.md 2.1).
 *
 * A theme package is data, not code: one theme.json + optional assets
 * (fonts/images). Parsing is fault-tolerant per plan rule "缺什么补什么":
 *  - unknown JSON fields are ignored
 *  - missing fields fall back to defaults / null
 *  - only a future formatVersion or a broken seed is rejected
 */
data class ThemePackageSpec(
    val formatVersion: Int = DEFAULT_FORMAT_VERSION,
    val id: String = "",
    val name: String = "",
    val author: String = "",
    /** Seed color, "#RRGGBB" or "#AARRGGBB". */
    val seed: String = "",
    /** tonal / amoled / muted / mono (case-insensitive, unknown falls back to tonal). */
    val darkStrategy: String = STRATEGY_TONAL,
    val fonts: ThemeFonts? = null,
    val images: ThemeImages? = null,
    /** Optional per-role overrides keyed as `color.<palette_role>`, e.g. "color.primary". */
    val `override`: Map<String, String>? = null,
) {

    val strategy: PaletteStrategy
        get() = when (darkStrategy.lowercase()) {
            STRATEGY_AMOLED -> PaletteStrategy.AMOLED
            STRATEGY_MUTED -> PaletteStrategy.MUTED
            STRATEGY_MONO -> PaletteStrategy.MONO
            else -> PaletteStrategy.TONAL
        }

    /** @return ARGB int or null when the seed string is missing/malformed. */
    fun seedArgb(): Int? = if (isWallpaperSeed) null else parseColor(seed)

    /**
     * "dynamic" marks the Material You wallpaper-seed theme: the app resolves
     * the seed at runtime from the system palette (Android 12+, falls back to
     * the builtin Sky theme below 12).
     */
    val isWallpaperSeed: Boolean
        get() = seed.trim().equals(SEEED_DYNAMIC, ignoreCase = true)

    fun isValid(): Boolean =
        formatVersion == DEFAULT_FORMAT_VERSION &&
            id.isNotBlank() && name.isNotBlank() &&
            (isWallpaperSeed || seedArgb() != null)

    companion object {

        const val DEFAULT_FORMAT_VERSION = 1
        const val SEEED_DYNAMIC = "dynamic"
        const val STRATEGY_TONAL = "tonal"
        const val STRATEGY_AMOLED = "amoled"
        const val STRATEGY_MUTED = "muted"
        const val STRATEGY_MONO = "mono"

        private val gson = Gson()

        /** Fault-tolerant parse; never throws. */
        fun parse(json: String): SpecParseResult = try {
            val spec = gson.fromJson(json, ThemePackageSpec::class.java)
            when {
                spec == null -> SpecParseResult.Error("theme.json 为空或不是 JSON 对象")
                spec.formatVersion > DEFAULT_FORMAT_VERSION ->
                    SpecParseResult.Error("主题包格式过新（v${spec.formatVersion}），请升级 App 后再导入")
                spec.id.isBlank() || spec.name.isBlank() ->
                    SpecParseResult.Error("主题包缺少 id 或 name")
                !(spec.isWallpaperSeed || spec.seedArgb() != null) ->
                    SpecParseResult.Error("种子色无效：${spec.seed}（应为 #RRGGBB、#AARRGGBB 或 dynamic）")
                else -> SpecParseResult.Ok(spec)
            }
        } catch (e: Exception) {
            SpecParseResult.Error("theme.json 解析失败：${e.message}")
        }

        /** "#RRGGBB" / "#AARRGGBB" → ARGB int, null when malformed. */
        fun parseColor(value: String?): Int? {
            if (value.isNullOrBlank()) return null
            val hex = value.removePrefix("#")
            return when (hex.length) {
                6 -> try {
                    0xFF000000.toInt() or hex.toLong(16).toInt()
                } catch (e: NumberFormatException) {
                    null
                }
                8 -> try {
                    hex.toLong(16).toInt()
                } catch (e: NumberFormatException) {
                    null
                }
                else -> null
            }
        }
    }
}

data class ThemeFonts(
    /** Role FontRole.UI, path inside the package assets/, e.g. "assets/ui.ttf". */
    val ui: String? = null,
    /** Role FontRole.TITLE. */
    val title: String? = null,
)

data class ThemeImages(
    /** Main background (day), path inside the package assets/ or absolute local path. */
    val background: String? = null,
    /** Main background (night); falls back to [background]. */
    val backgroundNight: String? = null,
    /** Book-info (detail) page background (day). Legacy slot: bookInfoBackgroundImgPath. */
    val bookInfo: String? = null,
    /** Book-info background (night); falls back to [bookInfo]. */
    val bookInfoNight: String? = null,
    /** Panel background (day). Legacy slot: panelBackgroundImgPath. */
    val panel: String? = null,
    /** Panel background (night); falls back to [panel]. */
    val panelNight: String? = null,
)

sealed class SpecParseResult {
    data class Ok(val spec: ThemePackageSpec) : SpecParseResult()
    data class Error(val message: String) : SpecParseResult()
}
