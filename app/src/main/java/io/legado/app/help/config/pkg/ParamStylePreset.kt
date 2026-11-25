package io.legado.app.help.config.pkg

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.pkg.runtime.ColorParseRuntime
import io.legado.app.help.config.pkg.runtime.GlassMaterialModes
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.removePref
import java.util.Locale

/**
 * 参数设置包「材质档位」单一来源（实底 / 透明 / 磨砂 / 液态）。
 *
 * - [GlassMaterialPreset] 负责底层 4 档光学渲染管线（`solid / clear / frosted / glass`）。
 * - [ParamStylePreset] 定义 4 档材质的完整 `@atmosphere` 参数基线，并统一承担
 *   清单应用（[applyToSurfaceGlobal] / [markCustomized]）、包内参数偏离派生（[inspectDeviation]）、
 *   运行时偏好读写（[applyToRuntimePrefs] / [clearRuntimePresetMatch] / [readRuntimePresetId] /
 *   [readRuntimePresetMatched]）以及不变量守护解析（[resolveEffectivePresetId] /
 *   [resolveRuntimePresetLabel] / [resolveSummaryLabel]）。
 */
object ParamStylePreset {

    data class Spec(
        val id: String,
        val label: String,
        val summaryLabel: String,
        val materialMode: String,
        val frostedLevel: Int,
        val liquidLevel: Int,
        val blurScale: Int,
        val tintScale: Int,
        val shellScale: Int,
        val strokeScale: Int,
        val strokeColor: String?,
        val surfaceAlpha: Int,
        val dialogAlpha: Int,
        val panelBorderAlpha: Int,
        val wallpaperBlur: Int
    )

    val solid = Spec(
        id = GlassMaterialModes.SOLID,
        label = "实底",
        summaryLabel = "实底",
        materialMode = GlassMaterialModes.SOLID,
        frostedLevel = 68,
        liquidLevel = 68,
        blurScale = 100,
        tintScale = 100,
        shellScale = 100,
        strokeScale = 100,
        strokeColor = null,
        surfaceAlpha = 100,
        dialogAlpha = 100,
        panelBorderAlpha = 100,
        wallpaperBlur = 0
    )

    val clear = Spec(
        id = GlassMaterialModes.CLEAR,
        label = "透明",
        summaryLabel = "透明",
        materialMode = GlassMaterialModes.CLEAR,
        frostedLevel = 68,
        liquidLevel = 68,
        blurScale = 100,
        tintScale = 100,
        shellScale = 100,
        strokeScale = 100,
        strokeColor = null,
        surfaceAlpha = 86,
        dialogAlpha = 92,
        panelBorderAlpha = 70,
        wallpaperBlur = 0
    )

    val frosted = Spec(
        id = GlassMaterialModes.FROSTED,
        label = "磨砂",
        summaryLabel = "磨砂",
        materialMode = GlassMaterialModes.FROSTED,
        frostedLevel = 72,
        liquidLevel = 68,
        blurScale = 115,
        tintScale = 110,
        shellScale = 100,
        strokeScale = 90,
        strokeColor = null,
        surfaceAlpha = 94,
        dialogAlpha = 95,
        panelBorderAlpha = 85,
        wallpaperBlur = 6
    )

    val glass = Spec(
        id = GlassMaterialModes.GLASS,
        label = "液态",
        summaryLabel = "液态",
        materialMode = GlassMaterialModes.GLASS,
        frostedLevel = 68,
        liquidLevel = 76,
        blurScale = 85,
        tintScale = 80,
        shellScale = 105,
        strokeScale = 120,
        strokeColor = null,
        surfaceAlpha = 90,
        dialogAlpha = 92,
        panelBorderAlpha = 100,
        wallpaperBlur = 2
    )

    val all: List<Spec> = listOf(solid, clear, frosted, glass)

    fun of(id: String?): Spec? {
        val normalized = id?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        return all.firstOrNull { it.id == normalized }
    }

    fun labelOf(id: String?): String? = of(id)?.label

    fun presetIdKey(isNight: Boolean): String =
        if (isNight) PreferKey.paramStylePresetIdN else PreferKey.paramStylePresetId

    fun presetMatchedKey(isNight: Boolean): String =
        if (isNight) PreferKey.glassPresetMatchedN else PreferKey.glassPresetMatched

    fun readRuntimePresetId(context: Context, isNight: Boolean): String? =
        context.getPrefString(presetIdKey(isNight))?.takeIf { it.isNotBlank() }

    fun readRuntimePresetMatched(context: Context, isNight: Boolean): Boolean =
        context.getPrefBoolean(presetMatchedKey(isNight), true)

    fun resolveEffectivePresetId(
        presetMatched: Boolean,
        stylePresetId: String?,
        materialMode: String?
    ): String? {
        if (!presetMatched) return null
        val presetSpec = of(stylePresetId)
        if (presetSpec != null && (materialMode == null || presetSpec.materialMode == materialMode)) {
            return presetSpec.id
        }
        return of(materialMode)?.id
    }

    fun resolveRuntimePresetLabel(
        presetMatched: Boolean,
        stylePresetId: String?,
        materialMode: String?
    ): String {
        if (!presetMatched) return "自定义"
        val effectiveId = resolveEffectivePresetId(
            presetMatched = true,
            stylePresetId = stylePresetId,
            materialMode = materialMode
        )
        return of(effectiveId)?.label ?: "默认材质"
    }

    fun resolveSummaryLabel(surfaceGlobal: SurfaceGlobalParams?): String {
        if (surfaceGlobal == null) return "默认材质"
        if (surfaceGlobal.presetMatched == false) return "自定义"
        val effectiveId = resolveEffectivePresetId(
            presetMatched = true,
            stylePresetId = surfaceGlobal.stylePresetId,
            materialMode = surfaceGlobal.materialMode
        )
        return of(effectiveId)?.summaryLabel ?: "默认材质"
    }

    data class TierDeviation(
        val tierSpec: Spec,
        val changedLabels: List<String>,
        val advancedChangedCount: Int
    ) {
        val diffCount: Int get() = changedLabels.size
        val isExactMatch: Boolean get() = changedLabels.isEmpty()
    }

    /**
     * Computes read-only UI deviation hints against [all] for badges and collapsible counters.
     *
     * Per change-safety R4, this function NEVER writes back `presetMatched` or `stylePresetId`:
     * preset selection explicitly sets matched state via [applyToSurfaceGlobal], while manual
     * slider/input edits explicitly mark customized via [markCustomized].
     */
    fun inspectDeviation(surfaceGlobal: SurfaceGlobalParams): TierDeviation {
        val tierSpec = of(surfaceGlobal.materialMode) ?: frosted
        val usesGlass = tierSpec.id == GlassMaterialModes.FROSTED ||
            tierSpec.id == GlassMaterialModes.GLASS
        val changed = ArrayList<String>(10)
        var advancedCount = 0

        if (usesGlass) {
            val activeLevel = if (tierSpec.id == GlassMaterialModes.FROSTED) {
                (surfaceGlobal.frostedLevel ?: tierSpec.frostedLevel).coerceIn(0, 100)
            } else {
                (surfaceGlobal.liquidLevel ?: tierSpec.liquidLevel).coerceIn(0, 100)
            }
            val tierLevel = if (tierSpec.id == GlassMaterialModes.FROSTED) {
                tierSpec.frostedLevel
            } else {
                tierSpec.liquidLevel
            }
            if (activeLevel != tierLevel) changed += "玻璃强度"
        }
        if ((surfaceGlobal.surfaceAlpha ?: 100).coerceIn(0, 100) != tierSpec.surfaceAlpha) {
            changed += "全局表面不透明度"
        }
        if ((surfaceGlobal.dialogAlpha ?: 100).coerceIn(0, 100) != tierSpec.dialogAlpha) {
            changed += "弹窗表面不透明度"
        }

        if (usesGlass) {
            if ((surfaceGlobal.blurScale ?: 100).coerceIn(0, 200) != tierSpec.blurScale) {
                changed += "模糊倍率"
                advancedCount++
            }
            if ((surfaceGlobal.tintScale ?: 100).coerceIn(0, 200) != tierSpec.tintScale) {
                changed += "着色倍率"
                advancedCount++
            }
            if ((surfaceGlobal.shellScale ?: 100).coerceIn(0, 200) != tierSpec.shellScale) {
                changed += "壳层倍率"
                advancedCount++
            }
            if ((surfaceGlobal.strokeScale ?: 100).coerceIn(0, 200) != tierSpec.strokeScale) {
                changed += "描边倍率"
                advancedCount++
            }
            val currentStroke = ColorParseRuntime.parseColorInput(
                surfaceGlobal.strokeColor,
                allowDecimalArgb = true
            )
            val specStroke = ColorParseRuntime.parseColorInput(
                tierSpec.strokeColor,
                allowDecimalArgb = true
            )
            if (currentStroke != specStroke) {
                changed += "描边颜色"
                advancedCount++
            }
        }
        if ((surfaceGlobal.panelBorderAlpha ?: 100).coerceIn(0, 100) != tierSpec.panelBorderAlpha) {
            changed += "边框不透明度"
            advancedCount++
        }
        val blur = (surfaceGlobal.wallpaperBlurDay ?: surfaceGlobal.wallpaperBlurNight ?: 0).coerceIn(0, 25)
        if (blur != tierSpec.wallpaperBlur) {
            changed += "壁纸模糊"
            advancedCount++
        }
        return TierDeviation(
            tierSpec = tierSpec,
            changedLabels = changed,
            advancedChangedCount = advancedCount
        )
    }

    fun markCustomized(current: SurfaceGlobalParams): SurfaceGlobalParams =
        current.copy(stylePresetId = null, presetMatched = false)

    fun applyToSurfaceGlobal(
        current: SurfaceGlobalParams?,
        presetId: String
    ): SurfaceGlobalParams {
        val base = current ?: SurfaceGlobalParams()
        val spec = of(presetId) ?: return base
        return base.copy(
            materialMode = spec.materialMode,
            stylePresetId = spec.id,
            frostedLevel = spec.frostedLevel,
            liquidLevel = spec.liquidLevel,
            blurScale = spec.blurScale,
            shellScale = spec.shellScale,
            tintScale = spec.tintScale,
            strokeScale = spec.strokeScale,
            strokeColor = spec.strokeColor,
            presetMatched = true,
            surfaceAlpha = spec.surfaceAlpha,
            dialogAlpha = spec.dialogAlpha,
            panelBorderAlpha = spec.panelBorderAlpha,
            wallpaperBlurDay = spec.wallpaperBlur,
            wallpaperBlurNight = spec.wallpaperBlur
        )
    }

    fun applyToRuntimePrefs(
        context: Context,
        presetId: String,
        isNight: Boolean
    ): Spec? {
        val spec = of(presetId) ?: return null
        GlassRuntimeWriter.writePresetSpec(context, spec, isNight)
        return spec
    }

    fun clearRuntimePresetMatch(context: Context, isNight: Boolean) {
        context.putPrefBoolean(presetMatchedKey(isNight), false)
        context.removePref(presetIdKey(isNight))
    }
}
