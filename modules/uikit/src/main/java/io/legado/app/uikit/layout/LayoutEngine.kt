package io.legado.app.uikit.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A2-3/A2-4 built-in official layout packages (docs/ui-rewrite-plan-v4.md 3.1).
 */
object BuiltinLayouts {

    val classic = LayoutPackageSpec(
        id = "classic", name = "经典底栏", author = "legado-H",
        navPosition = LayoutPackageSpec.NAV_BOTTOM,
        navVisibility = LayoutPackageSpec.VIS_ALWAYS,
        densityLevel = LayoutPackageSpec.DENSITY_STANDARD,
        glassEnabled = true, glassTransparency = 0.65f, glassBlur = 12f,
        shapeScale = 1f,
    )

    /** Floating capsule dock + glass — formalized home of the legacy floating bar. */
    val float = LayoutPackageSpec(
        id = "float", name = "悬浮玻璃", author = "legado-H",
        navPosition = LayoutPackageSpec.NAV_FLOAT,
        navVisibility = LayoutPackageSpec.VIS_ALWAYS,
        densityLevel = LayoutPackageSpec.DENSITY_STANDARD,
        glassEnabled = true, glassTransparency = 0.40f, glassBlur = 18f,
        shapeScale = 1.2f,
    )

    val side = LayoutPackageSpec(
        id = "side", name = "侧滑沉浸", author = "legado-H",
        navPosition = LayoutPackageSpec.NAV_SIDE,
        navVisibility = LayoutPackageSpec.VIS_ALWAYS,
        densityLevel = LayoutPackageSpec.DENSITY_STANDARD,
        glassEnabled = true, glassTransparency = 0.45f, glassBlur = 20f,
        shapeScale = 1.3f,
    )

    val minimal = LayoutPackageSpec(
        id = "minimal", name = "极简紧凑", author = "legado-H",
        navPosition = LayoutPackageSpec.NAV_SIDE,
        navVisibility = LayoutPackageSpec.VIS_SCROLL_HIDE,
        densityLevel = LayoutPackageSpec.DENSITY_COMPACT,
        glassEnabled = false, glassTransparency = 0.95f, glassBlur = 0f,
        shapeScale = 0.15f,
    )

    val all: List<LayoutPackageSpec> = listOf(classic, float, side, minimal)

    fun byId(id: String): LayoutPackageSpec? = all.firstOrNull { it.id == id }

    val default: LayoutPackageSpec = classic
}

/**
 * A2-1 layout engine state. The single entry point the whole App reads
 * structure values from (nav / density / glass / shape).
 *
 * Edit-in-package model: [applyLayout] with a CUSTOM spec (basedOn set);
 * no global delta layer. Persistence is handled by the app (LayoutStore).
 */
object LayoutEngine {

    var activeLayout by mutableStateOf(BuiltinLayouts.default)
        private set

    /** Bumped on every layout change so CompositionLocal consumers recompose. */
    var revision by mutableStateOf(0)
        private set

    fun applyLayout(spec: LayoutPackageSpec) {
        require(spec.isValid()) { "invalid layout spec: ${spec.id}" }
        activeLayout = spec
        bump()
    }

    /** Radius scale consumed by AppTheme / LocalAppRadiusScale. */
    val radiusScale: Float
        get() = activeLayout.shapeScale

    val densityFactor: Float
        get() = activeLayout.densityFactor

    val navPosition: String
        get() = activeLayout.navPosition

    val navVisibility: String
        get() = activeLayout.navVisibility

    val glassEnabled: Boolean
        get() = activeLayout.glassEnabled

    val glassAlpha: Float
        get() = activeLayout.glassTransparency

    val glassBlur: Float
        get() = activeLayout.glassBlur

    val iconStroke: Float
        get() = activeLayout.iconStroke

    private fun bump() {
        revision++
    }
}
