package io.legado.app.help.config.pkg

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.ui.main.GlassMaterialPreset
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import java.util.Locale

/**
 * 参数设置包「材质氛围预设模板」单一来源（与 [GlassMaterialPreset] 4 档渲染引擎解耦）。
 *
 * - [GlassMaterialPreset] 只负责底层 4 档光学渲染管线（`solid / clear / frosted / glass`）。
 * - [ParamStylePreset] 定义「快速切换」的一组完整 `@atmosphere` 氛围参数模板，并统一承担
 *   清单应用（[applyToSurfaceGlobal] / [markCustomized]）、运行时偏好读写
 *   （[applyToRuntimePrefs] / [clearRuntimePresetMatch] / [readRuntimePresetId] / [readRuntimePresetMatched]）
 *   以及不变量守护解析（[resolveEffectivePresetId] / [resolveRuntimePresetLabel] / [resolveSummaryLabel]）。
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
        id = GlassMaterialPreset.SOLID,
        label = "实底",
        summaryLabel = "实底",
        materialMode = GlassMaterialPreset.SOLID,
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
        id = GlassMaterialPreset.CLEAR,
        label = "透明",
        summaryLabel = "透明无玻璃",
        materialMode = GlassMaterialPreset.CLEAR,
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
        id = GlassMaterialPreset.FROSTED,
        label = "磨砂",
        summaryLabel = "磨砂玻璃",
        materialMode = GlassMaterialPreset.FROSTED,
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
        id = GlassMaterialPreset.GLASS,
        label = "液态",
        summaryLabel = "液态玻璃",
        materialMode = GlassMaterialPreset.GLASS,
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
        context.putPrefString(PreferKey.exploreGlassEffectMode, spec.materialMode)
        context.putPrefInt(PreferKey.frostedGlassLevel, spec.frostedLevel.coerceIn(0, 100))
        context.putPrefInt(PreferKey.liquidGlassLevel, spec.liquidLevel.coerceIn(0, 100))
        context.putPrefInt(PreferKey.exploreGlassBlurScale, spec.blurScale)
        context.putPrefInt(PreferKey.exploreGlassTintScale, spec.tintScale)
        context.putPrefInt(PreferKey.exploreGlassShellScale, spec.shellScale)
        context.putPrefInt(PreferKey.exploreGlassStrokeScale, spec.strokeScale)
        val cleanStroke = spec.strokeColor?.trim()?.takeIf { it.isNotBlank() }
        if (cleanStroke == null) {
            context.removePref(PreferKey.glassStrokeColor)
        } else {
            context.putPrefString(PreferKey.glassStrokeColor, cleanStroke)
        }
        context.putPrefInt(ThemeRuntimeKeys.uiLayoutAlpha(isNight), spec.surfaceAlpha.coerceIn(0, 100))
        context.putPrefInt(ThemeRuntimeKeys.dialogAlpha(isNight), spec.dialogAlpha.coerceIn(0, 100))
        context.putPrefInt(
            if (isNight) PreferKey.panelBorderAlphaN else PreferKey.panelBorderAlpha,
            spec.panelBorderAlpha.coerceIn(0, 100)
        )
        context.putPrefInt(
            if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
            spec.wallpaperBlur.coerceIn(0, 25)
        )
        context.putPrefString(presetIdKey(isNight), spec.id)
        context.putPrefBoolean(presetMatchedKey(isNight), true)
        return spec
    }

    fun clearRuntimePresetMatch(context: Context, isNight: Boolean) {
        context.putPrefBoolean(presetMatchedKey(isNight), false)
        context.removePref(presetIdKey(isNight))
    }
}
