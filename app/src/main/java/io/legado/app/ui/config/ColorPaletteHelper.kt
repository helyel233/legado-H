package io.legado.app.ui.config

import io.legado.app.help.config.pkg.ArtPackagePayload
import io.legado.app.help.config.pkg.ArtPaletteSpec
import io.legado.app.help.config.pkg.ArtTypographySpec
import io.legado.app.help.config.pkg.ArtWallpapersSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure JVM color math, curated palette definitions, and ArtPackage slot-edit helpers
 * backing [ColorPalettePickerDialog] and [ArtPackageEditorDialog].
 */
internal object ColorPaletteHelper {

    data class PaletteFamily(
        val name: String,
        val primaryArgb: Int,
        val shades: List<Int>
    )

    data class ArtSlotEditSeed(
        val palette: ArtPaletteSpec,
        val wallpapers: ArtWallpapersSpec,
        val typography: ArtTypographySpec
    )

    val neutralSwatches: List<Pair<String, Int>> = listOf(
        "纯白" to 0xFFFFFFFF.toInt(),
        "宣纸" to 0xFFFAF6ED.toInt(),
        "浅灰" to 0xFFF5F5F5.toInt(),
        "银雾" to 0xFFE0E0E0.toInt(),
        "中灰" to 0xFF9E9E9E.toInt(),
        "石板" to 0xFF616161.toInt(),
        "深灰" to 0xFF424242.toInt(),
        "夜卡片" to 0xFF2C2C2C.toInt(),
        "墨夜" to 0xFF1A1A1A.toInt(),
        "纯黑" to 0xFF000000.toInt()
    )

    val materialFamilies: List<PaletteFamily> = listOf(
        PaletteFamily(
            name = "正红",
            primaryArgb = 0xFFF44336.toInt(),
            shades = listOf(
                0xFFFFEBEE.toInt(), 0xFFFFCDD2.toInt(), 0xFFEF9A9A.toInt(), 0xFFE57373.toInt(), 0xFFEF5350.toInt(),
                0xFFF44336.toInt(), 0xFFE53935.toInt(), 0xFFD32F2F.toInt(), 0xFFC62828.toInt(), 0xFFB71C1C.toInt()
            )
        ),
        PaletteFamily(
            name = "粉红",
            primaryArgb = 0xFFE91E63.toInt(),
            shades = listOf(
                0xFFFCE4EC.toInt(), 0xFFF8BBD0.toInt(), 0xFFF48FB1.toInt(), 0xFFF06292.toInt(), 0xFFEC407A.toInt(),
                0xFFE91E63.toInt(), 0xFFD81B60.toInt(), 0xFFC2185B.toInt(), 0xFFAD1457.toInt(), 0xFF880E4F.toInt()
            )
        ),
        PaletteFamily(
            name = "紫色",
            primaryArgb = 0xFF9C27B0.toInt(),
            shades = listOf(
                0xFFF3E5F5.toInt(), 0xFFE1BEE7.toInt(), 0xFFCE93D8.toInt(), 0xFFBA68C8.toInt(), 0xFFAB47BC.toInt(),
                0xFF9C27B0.toInt(), 0xFF8E24AA.toInt(), 0xFF7B1FA2.toInt(), 0xFF6A1B9A.toInt(), 0xFF4A148C.toInt()
            )
        ),
        PaletteFamily(
            name = "深紫",
            primaryArgb = 0xFF673AB7.toInt(),
            shades = listOf(
                0xFFEDE7F6.toInt(), 0xFFD1C4E9.toInt(), 0xFFB39DDB.toInt(), 0xFF9575CD.toInt(), 0xFF7E57C2.toInt(),
                0xFF673AB7.toInt(), 0xFF5E35B1.toInt(), 0xFF512DA8.toInt(), 0xFF4527A0.toInt(), 0xFF311B92.toInt()
            )
        ),
        PaletteFamily(
            name = "靛蓝",
            primaryArgb = 0xFF3F51B5.toInt(),
            shades = listOf(
                0xFFE8EAF6.toInt(), 0xFFC5CAE9.toInt(), 0xFF9FA8DA.toInt(), 0xFF7986CB.toInt(), 0xFF5C6BC0.toInt(),
                0xFF3F51B5.toInt(), 0xFF3949AB.toInt(), 0xFF303F9F.toInt(), 0xFF283593.toInt(), 0xFF1A237E.toInt()
            )
        ),
        PaletteFamily(
            name = "蓝色",
            primaryArgb = 0xFF2196F3.toInt(),
            shades = listOf(
                0xFFE3F2FD.toInt(), 0xFFBBDEFB.toInt(), 0xFF90CAF9.toInt(), 0xFF64B5F6.toInt(), 0xFF42A5F5.toInt(),
                0xFF2196F3.toInt(), 0xFF1E88E5.toInt(), 0xFF1976D2.toInt(), 0xFF1565C0.toInt(), 0xFF0D47A1.toInt()
            )
        ),
        PaletteFamily(
            name = "天蓝",
            primaryArgb = 0xFF03A9F4.toInt(),
            shades = listOf(
                0xFFE1F5FE.toInt(), 0xFFB3E5FC.toInt(), 0xFF81D4FA.toInt(), 0xFF4FC3F7.toInt(), 0xFF29B6F6.toInt(),
                0xFF03A9F4.toInt(), 0xFF039BE5.toInt(), 0xFF0288D1.toInt(), 0xFF0277BD.toInt(), 0xFF01579B.toInt()
            )
        ),
        PaletteFamily(
            name = "青色",
            primaryArgb = 0xFF00BCD4.toInt(),
            shades = listOf(
                0xFFE0F7FA.toInt(), 0xFFB2EBF2.toInt(), 0xFF80DEEA.toInt(), 0xFF4DD0E1.toInt(), 0xFF26C6DA.toInt(),
                0xFF00BCD4.toInt(), 0xFF00ACC1.toInt(), 0xFF0097A7.toInt(), 0xFF00838F.toInt(), 0xFF006064.toInt()
            )
        ),
        PaletteFamily(
            name = "青绿",
            primaryArgb = 0xFF009688.toInt(),
            shades = listOf(
                0xFFE0F2F1.toInt(), 0xFFB2DFDB.toInt(), 0xFF80CBC4.toInt(), 0xFF4DB6AC.toInt(), 0xFF26A69A.toInt(),
                0xFF009688.toInt(), 0xFF00897B.toInt(), 0xFF00796B.toInt(), 0xFF00695C.toInt(), 0xFF004D40.toInt()
            )
        ),
        PaletteFamily(
            name = "绿色",
            primaryArgb = 0xFF4CAF50.toInt(),
            shades = listOf(
                0xFFE8F5E9.toInt(), 0xFFC8E6C9.toInt(), 0xFFA5D6A7.toInt(), 0xFF81C784.toInt(), 0xFF66BB6A.toInt(),
                0xFF4CAF50.toInt(), 0xFF43A047.toInt(), 0xFF388E3C.toInt(), 0xFF2E7D32.toInt(), 0xFF1B5E20.toInt()
            )
        ),
        PaletteFamily(
            name = "浅绿",
            primaryArgb = 0xFF8BC34A.toInt(),
            shades = listOf(
                0xFFF1F8E9.toInt(), 0xFFDCEDC8.toInt(), 0xFFC5E1A5.toInt(), 0xFFAED581.toInt(), 0xFF9CCC65.toInt(),
                0xFF8BC34A.toInt(), 0xFF7CB342.toInt(), 0xFF689F38.toInt(), 0xFF558B2F.toInt(), 0xFF33691E.toInt()
            )
        ),
        PaletteFamily(
            name = "青柠",
            primaryArgb = 0xFFCDDC39.toInt(),
            shades = listOf(
                0xFFF9FBE7.toInt(), 0xFFF0F4C3.toInt(), 0xFFE6EE9C.toInt(), 0xFFDCE775.toInt(), 0xFFD4E157.toInt(),
                0xFFCDDC39.toInt(), 0xFFC0CA33.toInt(), 0xFFAFB42B.toInt(), 0xFF9E9D24.toInt(), 0xFF827717.toInt()
            )
        ),
        PaletteFamily(
            name = "明黄",
            primaryArgb = 0xFFFFEB3B.toInt(),
            shades = listOf(
                0xFFFFFDE7.toInt(), 0xFFFFF9C4.toInt(), 0xFFFFF59D.toInt(), 0xFFFFF176.toInt(), 0xFFFFEE58.toInt(),
                0xFFFFEB3B.toInt(), 0xFFFDD835.toInt(), 0xFFFBC02D.toInt(), 0xFFF9A825.toInt(), 0xFFF57F17.toInt()
            )
        ),
        PaletteFamily(
            name = "琥珀",
            primaryArgb = 0xFFFFC107.toInt(),
            shades = listOf(
                0xFFFFF8E1.toInt(), 0xFFFFECB3.toInt(), 0xFFFFE082.toInt(), 0xFFFFD54F.toInt(), 0xFFFFCA28.toInt(),
                0xFFFFC107.toInt(), 0xFFFFB300.toInt(), 0xFFFFA000.toInt(), 0xFFFF8F00.toInt(), 0xFFFF6F00.toInt()
            )
        ),
        PaletteFamily(
            name = "橙色",
            primaryArgb = 0xFFFF9800.toInt(),
            shades = listOf(
                0xFFFFF3E0.toInt(), 0xFFFFE0B2.toInt(), 0xFFFFCC80.toInt(), 0xFFFFB74D.toInt(), 0xFFFFA726.toInt(),
                0xFFFF9800.toInt(), 0xFFFB8C00.toInt(), 0xFFF57C00.toInt(), 0xFFEF6C00.toInt(), 0xFFE65100.toInt()
            )
        ),
        PaletteFamily(
            name = "深橙",
            primaryArgb = 0xFFFF5722.toInt(),
            shades = listOf(
                0xFFFBE9E7.toInt(), 0xFFFFCCBC.toInt(), 0xFFFFAB91.toInt(), 0xFFFF8A65.toInt(), 0xFFFF7043.toInt(),
                0xFFFF5722.toInt(), 0xFFF4511E.toInt(), 0xFFE64A19.toInt(), 0xFFD84315.toInt(), 0xFFBF360C.toInt()
            )
        ),
        PaletteFamily(
            name = "棕色",
            primaryArgb = 0xFF795548.toInt(),
            shades = listOf(
                0xFFEFEBE9.toInt(), 0xFFD7CCC8.toInt(), 0xFFBCAAA4.toInt(), 0xFFA1887F.toInt(), 0xFF8D6E63.toInt(),
                0xFF795548.toInt(), 0xFF6D4C41.toInt(), 0xFF5D4037.toInt(), 0xFF4E342E.toInt(), 0xFF3E2723.toInt()
            )
        ),
        PaletteFamily(
            name = "蓝灰",
            primaryArgb = 0xFF607D8B.toInt(),
            shades = listOf(
                0xFFECEFF1.toInt(), 0xFFCFD8DC.toInt(), 0xFFB0BEC5.toInt(), 0xFF90A4AE.toInt(), 0xFF78909C.toInt(),
                0xFF607D8B.toInt(), 0xFF546E7A.toInt(), 0xFF455A64.toInt(), 0xFF37474F.toInt(), 0xFF263238.toInt()
            )
        )
    )

    fun parseColorInput(
        raw: String?,
        allowDecimalArgb: Boolean = false,
        preserveAlpha: Boolean = false
    ): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.startsWith("#")) {
            val hex = text.substring(1)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val argbLong = when (hex.length) {
                3 -> {
                    val expanded = buildString(8) {
                        append("FF")
                        hex.forEach { ch ->
                            append(ch)
                            append(ch)
                        }
                    }
                    expanded.toLongOrNull(16)
                }
                4 -> {
                    val expanded = buildString(8) {
                        if (preserveAlpha) {
                            append(hex[0])
                            append(hex[0])
                        } else {
                            append("FF")
                        }
                        for (i in 1..3) {
                            append(hex[i])
                            append(hex[i])
                        }
                    }
                    expanded.toLongOrNull(16)
                }
                6 -> ("FF$hex").toLongOrNull(16)
                8 -> {
                    val resolved = if (preserveAlpha) hex else "FF${hex.substring(2)}"
                    resolved.toLongOrNull(16)
                }
                else -> null
            } ?: return null
            return argbLong.toInt()
        }
        if (allowDecimalArgb) {
            return text.toIntOrNull()
        }
        if (text.length == 6 || text.length == 8) {
            if (text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                val normalized = when {
                    text.length == 6 -> "FF$text"
                    preserveAlpha -> text
                    else -> "FF${text.substring(2)}"
                }
                return normalized.toLongOrNull(16)?.toInt()
            }
        }
        return null
    }

    fun formatHex6(argb: Int): String {
        val rgb = argb and 0x00FFFFFF
        return String.format(Locale.US, "#%06X", rgb)
    }

    fun rgbToHsv(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        val hue = when {
            delta <= 1e-6f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { h -> if (h < 0f) h + 360f else h }

        val saturation = if (maxC <= 1e-6f) 0f else delta / maxC
        return Triple(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), maxC.coerceIn(0f, 1f))
    }

    fun hsvToArgb(hue: Float, saturation: Float, value: Float, alpha: Int = 255): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rf + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((gf + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((bf + m) * 255f).roundToInt().coerceIn(0, 255)
        val a = alpha.coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun contrastOnColor(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val darkness = 1.0 - (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (darkness < 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    fun findNearestFamilyIndex(argb: Int): Int {
        val (h, s, _) = rgbToHsv(argb)
        if (s < 0.08f) {
            return materialFamilies.lastIndex // 蓝灰作为中性近似
        }
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        materialFamilies.forEachIndexed { idx, family ->
            val (fh, fs, _) = rgbToHsv(family.primaryArgb)
            val rawDiff = abs(h - fh)
            val hueDiff = min(rawDiff, 360f - rawDiff)
            val satDiff = abs(s - fs) * 30f
            val score = hueDiff + satDiff
            if (score < bestDist) {
                bestDist = score
                bestIdx = idx
            }
        }
        return bestIdx
    }

    fun resolveArtSlotForEdit(
        art: ArtPackagePayload,
        preferNightSlot: Boolean
    ): ArtSlotEditSeed {
        val palette = if (preferNightSlot) {
            art.nightPalette ?: art.dayPalette ?: ArtPaletteSpec()
        } else {
            art.dayPalette ?: art.nightPalette ?: ArtPaletteSpec()
        }
        val wallpapers = if (preferNightSlot) {
            art.nightWallpapers ?: art.dayWallpapers ?: ArtWallpapersSpec()
        } else {
            art.dayWallpapers ?: art.nightWallpapers ?: ArtWallpapersSpec()
        }
        val typography = if (preferNightSlot) {
            art.nightTypography ?: art.dayTypography ?: ArtTypographySpec()
        } else {
            art.dayTypography ?: art.nightTypography ?: ArtTypographySpec()
        }
        return ArtSlotEditSeed(palette = palette, wallpapers = wallpapers, typography = typography)
    }

    fun applyArtSlotEdit(
        original: ArtPackagePayload,
        preferNightSlot: Boolean,
        updatedPalette: ArtPaletteSpec,
        updatedWallpapers: ArtWallpapersSpec,
        updatedTypography: ArtTypographySpec,
        defaultCoverPath: String?,
        syncBothSlots: Boolean = false
    ): ArtPackagePayload {
        val nextDayPalette = when {
            syncBothSlots || !preferNightSlot -> updatedPalette
            original.dayPalette == null -> updatedPalette
            else -> original.dayPalette
        }
        val nextNightPalette = when {
            syncBothSlots || preferNightSlot -> updatedPalette
            original.nightPalette == null -> updatedPalette
            else -> original.nightPalette
        }
        val nextDayWallpapers = when {
            syncBothSlots || !preferNightSlot -> updatedWallpapers
            original.dayWallpapers == null -> updatedWallpapers
            else -> original.dayWallpapers
        }
        val nextNightWallpapers = when {
            syncBothSlots || preferNightSlot -> updatedWallpapers
            original.nightWallpapers == null -> updatedWallpapers
            else -> original.nightWallpapers
        }
        val nextDayTypography = when {
            syncBothSlots || !preferNightSlot -> updatedTypography
            original.dayTypography == null -> updatedTypography
            else -> original.dayTypography
        }
        val nextNightTypography = when {
            syncBothSlots || preferNightSlot -> updatedTypography
            original.nightTypography == null -> updatedTypography
            else -> original.nightTypography
        }

        return original.copy(
            dayPalette = nextDayPalette,
            nightPalette = nextNightPalette,
            dayWallpapers = nextDayWallpapers,
            nightWallpapers = nextNightWallpapers,
            dayTypography = nextDayTypography,
            nightTypography = nextNightTypography,
            defaultCoverPath = defaultCoverPath?.trim()?.ifBlank { null }
        )
    }
}
