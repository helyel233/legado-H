package io.legado.app.uikit.layout

import com.google.gson.Gson

/**
 * A2-1 layout package v1 (docs/ui-rewrite-plan-v4.md 3.1).
 *
 * Structure-only skin: nav / density / glass / shape — global fields only,
 * no per-screen fields (iron rule). Edit-in-package model (V4.8): adjusting
 * any value while a package is active creates/updates a CUSTOM package whose
 * [basedOn] records the package it was copied from.
 *
 * Parsing is fault-tolerant: unknown fields ignored, missing → defaults.
 */
data class LayoutPackageSpec(
    val formatVersion: Int = DEFAULT_FORMAT_VERSION,
    val id: String = "",
    val name: String = "",
    val author: String = "",
    /** bottom / float / side / top */
    val navPosition: String = NAV_BOTTOM,
    /** always / scrollHide */
    val navVisibility: String = VIS_ALWAYS,
    /** compact / standard / comfortable */
    val densityLevel: String = DENSITY_STANDARD,
    val glassEnabled: Boolean = false,
    /** 0..1 */
    val glassTransparency: Float = 0.65f,
    /** blur radius dp, 0..24 */
    val glassBlur: Float = 12f,
    /** square(0) .. rounded(0.5) .. pill-scale(1.0+) */
    val shapeScale: Float = 1f,
    /** icon stroke baseline dp */
    val iconStroke: Float = 1.8f,
    /** id of the package this custom package was copied from */
    val basedOn: String? = null,
) {

    val densityFactor: Float
        get() = when (densityLevel) {
            DENSITY_COMPACT -> 0.85f
            DENSITY_COMFORTABLE -> 1.15f
            else -> 1f
        }

    fun isValid(): Boolean =
        formatVersion == DEFAULT_FORMAT_VERSION &&
            id.isNotBlank() && name.isNotBlank() &&
            navPosition in NAV_POSITIONS &&
            densityLevel in DENSITY_LEVELS

    companion object {
        const val DEFAULT_FORMAT_VERSION = 1
        const val NAV_BOTTOM = "bottom"
        const val NAV_FLOAT = "float"
        const val NAV_SIDE = "side"
        const val NAV_TOP = "top"
        val NAV_POSITIONS = listOf(NAV_BOTTOM, NAV_FLOAT, NAV_SIDE, NAV_TOP)
        const val VIS_ALWAYS = "always"
        const val VIS_SCROLL_HIDE = "scrollHide"
        val VIS_MODES = listOf(VIS_ALWAYS, VIS_SCROLL_HIDE)
        const val DENSITY_COMPACT = "compact"
        const val DENSITY_STANDARD = "standard"
        const val DENSITY_COMFORTABLE = "comfortable"
        val DENSITY_LEVELS = listOf(DENSITY_COMPACT, DENSITY_STANDARD, DENSITY_COMFORTABLE)

        private val gson = Gson()

        fun parse(json: String): LayoutParseResult = try {
            val spec = gson.fromJson(json, LayoutPackageSpec::class.java)
            when {
                spec == null -> LayoutParseResult.Error("layout.json 为空或不是 JSON 对象")
                spec.formatVersion > DEFAULT_FORMAT_VERSION ->
                    LayoutParseResult.Error("界面包格式过新（v${spec.formatVersion}），请升级 App 后再导入")
                spec.id.isBlank() || spec.name.isBlank() ->
                    LayoutParseResult.Error("界面包缺少 id 或 name")
                else -> LayoutParseResult.Ok(spec)
            }
        } catch (e: Exception) {
            LayoutParseResult.Error("layout.json 解析失败：${e.message}")
        }
    }
}

sealed class LayoutParseResult {
    data class Ok(val spec: LayoutPackageSpec) : LayoutParseResult()
    data class Error(val message: String) : LayoutParseResult()
}
