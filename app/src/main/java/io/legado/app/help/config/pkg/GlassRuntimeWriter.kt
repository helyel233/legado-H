package io.legado.app.help.config.pkg

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.KitGlassBinding
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref

/**
 * Single runtime preference writer for glass material mode, levels, optical scales,
 * day/night-separated preset state ([PreferKey.glassPresetMatched] / [PreferKey.glassPresetMatchedN]
 * and [PreferKey.paramStylePresetId] / [PreferKey.paramStylePresetIdN]), and optional `@atmosphere`
 * opacity/blur parameters.
 *
 * Shared by [AppearanceKitManager.applyGlassBinding], [ParamStylePreset.applyToRuntimePrefs], and
 * [UnifiedPackageRepository.applyParamAtmosphere] so all three write identical day/night slot keys.
 */
internal object GlassRuntimeWriter {

    fun writePresetSpec(
        context: Context,
        spec: ParamStylePreset.Spec,
        isNight: Boolean
    ) {
        writeAtmosphere(
            context = context,
            isNight = isNight,
            activateGlassNow = true,
            materialMode = spec.materialMode,
            frostedLevel = spec.frostedLevel,
            liquidLevel = spec.liquidLevel,
            blurScale = spec.blurScale,
            tintScale = spec.tintScale,
            shellScale = spec.shellScale,
            strokeScale = spec.strokeScale,
            strokeColor = spec.strokeColor,
            removeBlankStrokeColor = true,
            hardwareBlur = null,
            animatedSolid = null,
            presetMatched = true,
            stylePresetId = spec.id,
            surfaceAlpha = spec.surfaceAlpha,
            dialogAlpha = spec.dialogAlpha,
            panelBorderAlpha = spec.panelBorderAlpha,
            wallpaperBlur = spec.wallpaperBlur
        )
    }

    fun writeGlassBinding(
        context: Context,
        glass: KitGlassBinding,
        isNight: Boolean
    ) {
        val effectivePresetId = if (glass.presetMatched) {
            ParamStylePreset.resolveEffectivePresetId(
                presetMatched = true,
                stylePresetId = glass.stylePresetId,
                materialMode = glass.mode
            )
        } else {
            null
        }
        writeAtmosphere(
            context = context,
            isNight = isNight,
            activateGlassNow = true,
            materialMode = glass.mode,
            frostedLevel = glass.frostedLevel,
            liquidLevel = glass.liquidLevel,
            blurScale = glass.blurScale,
            tintScale = glass.tintScale,
            shellScale = glass.shellScale,
            strokeScale = glass.strokeScale,
            strokeColor = glass.strokeColor,
            removeBlankStrokeColor = true,
            hardwareBlur = glass.hardwareBlur,
            animatedSolid = glass.animatedSolid,
            presetMatched = glass.presetMatched,
            stylePresetId = effectivePresetId,
            surfaceAlpha = null,
            dialogAlpha = null,
            panelBorderAlpha = null,
            wallpaperBlur = null
        )
    }

    fun writeSurfaceGlobal(
        context: Context,
        g: SurfaceGlobalParams,
        isNight: Boolean,
        activateGlassNow: Boolean
    ) {
        val blur = if (isNight) {
            g.wallpaperBlurNight ?: g.wallpaperBlurDay
        } else {
            g.wallpaperBlurDay ?: g.wallpaperBlurNight
        }
        writeAtmosphere(
            context = context,
            isNight = isNight,
            activateGlassNow = activateGlassNow,
            materialMode = g.materialMode,
            frostedLevel = g.frostedLevel,
            liquidLevel = g.liquidLevel,
            blurScale = g.blurScale,
            tintScale = g.tintScale,
            shellScale = g.shellScale,
            strokeScale = g.strokeScale,
            strokeColor = g.strokeColor,
            removeBlankStrokeColor = true,
            hardwareBlur = g.hardwareBlur,
            animatedSolid = g.animatedSolid,
            presetMatched = g.presetMatched,
            stylePresetId = g.stylePresetId,
            surfaceAlpha = g.surfaceAlpha,
            dialogAlpha = g.dialogAlpha,
            panelBorderAlpha = g.panelBorderAlpha,
            wallpaperBlur = blur
        )
    }

    private fun writeAtmosphere(
        context: Context,
        isNight: Boolean,
        activateGlassNow: Boolean,
        materialMode: String?,
        frostedLevel: Int?,
        liquidLevel: Int?,
        blurScale: Int?,
        tintScale: Int?,
        shellScale: Int?,
        strokeScale: Int?,
        strokeColor: String?,
        removeBlankStrokeColor: Boolean,
        hardwareBlur: Boolean?,
        animatedSolid: Boolean?,
        presetMatched: Boolean?,
        stylePresetId: String?,
        surfaceAlpha: Int?,
        dialogAlpha: Int?,
        panelBorderAlpha: Int?,
        wallpaperBlur: Int?
    ) {
        if (activateGlassNow) {
            materialMode?.takeIf { it.isNotBlank() }?.let {
                context.putPrefString(PreferKey.exploreGlassEffectMode, it)
            }
            frostedLevel?.let { context.putPrefInt(PreferKey.frostedGlassLevel, it.coerceIn(0, 100)) }
            liquidLevel?.let { context.putPrefInt(PreferKey.liquidGlassLevel, it.coerceIn(0, 100)) }
            blurScale?.let { context.putPrefInt(PreferKey.exploreGlassBlurScale, it) }
            shellScale?.let { context.putPrefInt(PreferKey.exploreGlassShellScale, it) }
            tintScale?.let { context.putPrefInt(PreferKey.exploreGlassTintScale, it) }
            strokeScale?.let { context.putPrefInt(PreferKey.exploreGlassStrokeScale, it) }
            if (removeBlankStrokeColor) {
                putOrRemovePref(context, PreferKey.glassStrokeColor, strokeColor)
            } else if (strokeColor != null) {
                context.putPrefString(PreferKey.glassStrokeColor, strokeColor)
            }
            hardwareBlur?.let { context.putPrefBoolean(PreferKey.exploreGlassHardwareBlur, it) }
            animatedSolid?.let { context.putPrefBoolean(PreferKey.exploreGlassAnimatedSolid, it) }
        }

        val presetIdKey = ParamStylePreset.presetIdKey(isNight)
        val presetMatchedKey = ParamStylePreset.presetMatchedKey(isNight)
        when (presetMatched) {
            true -> {
                context.putPrefBoolean(presetMatchedKey, true)
                val effectivePresetId = stylePresetId?.takeIf { it.isNotBlank() }
                    ?: materialMode?.takeIf { it.isNotBlank() }
                putOrRemovePref(context, presetIdKey, effectivePresetId)
            }
            false -> {
                context.putPrefBoolean(presetMatchedKey, false)
                context.removePref(presetIdKey)
            }
            null -> {
                val explicitPresetId = stylePresetId?.takeIf { it.isNotBlank() }
                if (explicitPresetId != null) {
                    context.putPrefString(presetIdKey, explicitPresetId)
                } else {
                    val storedPresetId = context.getPrefString(presetIdKey)?.takeIf { it.isNotBlank() }
                    val storedSpec = ParamStylePreset.of(storedPresetId)
                    val targetMode = materialMode?.takeIf { it.isNotBlank() }
                    if (storedPresetId != null && (storedSpec == null || (targetMode != null && storedSpec.materialMode != targetMode))) {
                        context.removePref(presetIdKey)
                    }
                }
            }
        }

        surfaceAlpha?.let {
            context.putPrefInt(ThemeRuntimeKeys.uiLayoutAlpha(isNight), it.coerceIn(0, 100))
        }
        dialogAlpha?.let {
            context.putPrefInt(ThemeRuntimeKeys.dialogAlpha(isNight), it.coerceIn(0, 100))
        }
        panelBorderAlpha?.let {
            context.putPrefInt(
                if (isNight) PreferKey.panelBorderAlphaN else PreferKey.panelBorderAlpha,
                it.coerceIn(0, 100)
            )
        }
        wallpaperBlur?.let {
            context.putPrefInt(
                if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring,
                it.coerceIn(0, 25)
            )
        }
    }

    private fun putOrRemovePref(context: Context, key: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotBlank() }
        if (clean == null) {
            context.removePref(key)
        } else {
            context.putPrefString(key, clean)
        }
    }
}
