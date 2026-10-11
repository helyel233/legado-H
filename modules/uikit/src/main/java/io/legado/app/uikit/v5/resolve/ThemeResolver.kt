package io.legado.app.uikit.v5.resolve

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.legado.app.theme.palette.PaletteRole
import io.legado.app.theme.pack.ThemePackageSpec
import io.legado.app.theme.pack.resolvePalette
import io.legado.app.uikit.theme.BuiltinThemes
import io.legado.app.uikit.v5.contract.AppSemanticColors
import io.legado.app.uikit.v5.contract.ReaderTheme

/**
 * P0-4 美术取值唯一入口（docs/ui-v5-skeleton-first.md 4.1 / 手册 0.3）。
 *
 * 主题包只管美术：颜色（种子色派生 38 角色 + 包内 override）、字体、背景图、纹理。
 * 形状、间距、动效一个都不管 —— 那些在 [LayoutResolver]。
 *
 * 全部输出是 Compose State：换包 / 日夜切换 / 壁纸种子变化都会自动重组整棵树，
 * **不需要 recreate()**（旧体系 EventBus.RECREATE 的路径在 P1 退役）。
 */
object ThemeResolver {

    var isDark by mutableStateOf(true)
        private set

    /** 每次取值变化 +1，供需要显式 key 的调用方使用。 */
    var revision by mutableStateOf(0)
        private set

    var activeTheme: ThemePackageSpec by mutableStateOf(BuiltinThemes.default)
        private set

    /** 动态取色（Android 12+ 壁纸主色）；null 时回落到包自身的种子色。 */
    var wallpaperSeed: Int? by mutableStateOf(null)
        private set

    private var palette: Map<PaletteRole, Int> =
        BuiltinThemes.default.resolvePalette(dark = true)

    private val fontCache = HashMap<String, FontFamily>()

    val uiFontPath: String get() = activeTheme.fonts?.ui.orEmpty()
    val titleFontPath: String get() = activeTheme.fonts?.title.orEmpty()

    // ---------------------------------------------------------------- 输入

    fun applyTheme(spec: ThemePackageSpec) {
        require(spec.isValid()) { "invalid theme spec: ${spec.id}" }
        activeTheme = spec
        recompute()
    }

    fun applyDark(dark: Boolean) {
        if (isDark == dark) return
        isDark = dark
        recompute()
    }

    fun applyWallpaperSeed(seed: Int?) {
        wallpaperSeed = seed
        recompute()
    }

    // ---------------------------------------------------------------- 输出

    /** 语义色表：界面只认名字不认值。 */
    fun colors(): AppSemanticColors {
        val p = palette
        return AppSemanticColors(
            background = p.role(PaletteRole.BACKGROUND),
            surface = p.role(PaletteRole.SURFACE),
            surfaceVariant = p.role(PaletteRole.SURFACE_VARIANT),
            primary = p.role(PaletteRole.PRIMARY),
            onPrimary = p.role(PaletteRole.ON_PRIMARY),
            secondary = p.role(PaletteRole.TERTIARY),
            onSurface = p.role(PaletteRole.ON_SURFACE),
            onSurfaceVariant = p.role(PaletteRole.ON_SURFACE_VARIANT),
            outline = p.role(PaletteRole.OUTLINE_VARIANT),
            // 状态色不跟随种子色：成功=绿、警告=橙 的语义必须稳定可辨
            success = Color(if (isDark) STATUS_SUCCESS_DARK else STATUS_SUCCESS_LIGHT),
            warning = Color(if (isDark) STATUS_WARNING_DARK else STATUS_WARNING_LIGHT),
            error = p.role(PaletteRole.ERROR),
        )
    }

    /** 背景图（三个槽 × 日夜，夜间回落到日间图） */
    fun backgroundPath(scene: BackgroundScene): String? {
        val img = activeTheme.images ?: return null
        return when (scene) {
            BackgroundScene.MAIN -> if (isDark) img.backgroundNight ?: img.background else img.background
            BackgroundScene.BOOK_INFO -> if (isDark) img.bookInfoNight ?: img.bookInfo else img.bookInfo
            BackgroundScene.PANEL -> if (isDark) img.panelNight ?: img.panel else img.panel
        }
    }

    /**
     * 交给读书页的一整套值（ReaderBridge 用）。
     * 只在读书页主动来问时才调用；默认 AUTONOMOUS 模式下根本不会被走到。
     */
    fun readerTheme(): ReaderTheme {
        val p = palette
        return ReaderTheme(
            background = p.role(PaletteRole.BACKGROUND),
            text = p.role(PaletteRole.ON_BACKGROUND),
            accent = p.role(PaletteRole.TERTIARY),
            divider = p.role(PaletteRole.OUTLINE_VARIANT),
            uiFontPath = uiFontPath,
            backgroundImagePath = backgroundPath(BackgroundScene.MAIN),
            texturePath = null, // P3 主题包 texture 字段上线后接入
            textureAlpha = 0f,
        )
    }

    /** 正文对比度自检（WCAG），供单测与包编辑器提示使用。 */
    fun bodyContrast(): Double {
        val p = palette
        return io.legado.app.theme.palette.SeedPalette.contrastRatio(
            p[PaletteRole.ON_SURFACE] ?: 0xFFFFFFFF.toInt(),
            p[PaletteRole.SURFACE] ?: 0xFF000000.toInt(),
        )
    }

    /**
     * P3-1 包字体 → Compose FontFamily（带缓存：字体文件加载昂贵）。
     * 路径来自主题包 fonts.*（绝对路径），文件不存在或解析失败回 null（系统字体）。
     */
    fun uiFontFamily(): FontFamily? = loadedFont(uiFontPath)

    fun titleFontFamily(): FontFamily? = loadedFont(titleFontPath)

    private fun loadedFont(path: String): FontFamily? {
        if (path.isBlank()) return null
        synchronized(fontCache) {
            fontCache[path]?.let { return it }
            val family = try {
                val file = java.io.File(path)
                if (file.exists()) FontFamily(Font(file)) else null
            } catch (_: Exception) {
                null
            }
            if (family != null) fontCache[path] = family
            return family
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun recompute() {
        palette = activeTheme.resolvePalette(dark = isDark, wallpaperSeed = wallpaperSeed)
        revision++
    }

    private fun Map<PaletteRole, Int>.role(role: PaletteRole): Color =
        Color(this[role] ?: 0xFF808080.toInt())

    private const val STATUS_SUCCESS_LIGHT = 0xFF2E7D5B.toInt()
    private const val STATUS_SUCCESS_DARK = 0xFF4CC38A.toInt()
    private const val STATUS_WARNING_LIGHT = 0xFFB26A1A.toInt()
    private const val STATUS_WARNING_DARK = 0xFFE8A33D.toInt()
}

/** 三个背景槽（对应 V4.8 裁决：旧版 3 个背景图槽全部进主题包）。 */
enum class BackgroundScene { MAIN, BOOK_INFO, PANEL }
