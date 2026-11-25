package io.legado.app.help.config.pkg

import com.google.gson.JsonElement
import java.util.Locale

enum class UiScope(val id: String) {
    TOP_BAR("topBar"),
    BOTTOM_BAR("bottomBar"),
    BOOKSHELF_CARD("bookshelfCard"),
    DIALOG("dialog");

    companion object {
        fun fromId(raw: String?): UiScope? {
            return entries.firstOrNull { it.id == raw }
        }
    }
}

data class UnifiedPackageManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val kind: PackageKind,
    val id: String,
    val name: String,
    val updatedAt: Long = 0L,
    val basePackageId: String? = null,
    val contentHash: String? = null,
    val art: ArtPackagePayload? = null,
    val param: ParamPackagePayload? = null,
    val coverSlot: CoverSlotPackagePayload? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MANIFEST_FILE_NAME = "manifest.json"
    }
}

data class ArtPackagePayload(
    val dayPalette: ArtPaletteSpec? = null,
    val nightPalette: ArtPaletteSpec? = null,
    val dayWallpapers: ArtWallpapersSpec? = null,
    val nightWallpapers: ArtWallpapersSpec? = null,
    val dayTypography: ArtTypographySpec? = null,
    val nightTypography: ArtTypographySpec? = null,
    val icons: Map<String, String> = emptyMap(),
    val covers: List<String> = emptyList(),
    val defaultCoverPath: String? = null,
    val mounts: ArtAssetMounts = ArtAssetMounts(),
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class ArtPaletteSpec(
    val primaryColor: String? = null,
    val accentColor: String? = null,
    val backgroundColor: String? = null,
    val bottomBackground: String? = null,
    val cardColor: String? = null,
    val mutedColor: String? = null,
    val searchFieldBackgroundColor: String? = null,
    val tabBackgroundColor: String? = null,
    val shelfColor: String? = null,
    val panelBorderColor: String? = null,
    val uiFontColor: String? = null,
    val titleFontColor: String? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class ArtWallpapersSpec(
    val main: String? = null,
    val mainCrop: String? = null,
    val topBar: String? = null,
    val topBarCropLeft: Float? = null,
    val topBarCropTop: Float? = null,
    val topBarCropRight: Float? = null,
    val topBarCropBottom: Float? = null,
    val topBarAlpha: Int? = null,
    val bottomBar: String? = null,
    val sidebar: String? = null,
    val bookInfo: String? = null,
    val panel: String? = null,
    val panelScaleType: String? = null,
    val welcome: String? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class ArtTypographySpec(
    val uiFontPath: String? = null,
    val titleFontPath: String? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class ArtAssetMounts(
    val dayIconSlotId: String? = null,
    val nightIconSlotId: String? = null,
    val dayCoverSlotId: String? = null,
    val nightCoverSlotId: String? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class ParamPackagePayload(
    val surfaceGlobal: SurfaceGlobalParams? = null,
    val surfaceScopes: SurfaceScopesParams? = null,
    val layout: LayoutParams? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class SurfaceGlobalParams(
    val materialMode: String? = null,
    val frostedLevel: Int? = null,
    val liquidLevel: Int? = null,
    val blurScale: Int? = null,
    val shellScale: Int? = null,
    val tintScale: Int? = null,
    val strokeScale: Int? = null,
    val strokeColor: String? = null,
    val presetMatched: Boolean? = null,
    val stylePresetId: String? = null,
    val hardwareBlur: Boolean? = null,
    val animatedSolid: Boolean? = null,
    val cornerBaseScale: Float? = null,
    val surfaceAlpha: Int? = null,
    val dialogAlpha: Int? = null,
    val panelBorderAlpha: Int? = null,
    val cardShadow: Int? = null,
    val cardBackgroundBlur: Float? = null,
    val wallpaperBlurDay: Int? = null,
    val wallpaperBlurNight: Int? = null,
    val uiCornerSearchFollow: Boolean? = null,
    val uiCornerReplyFollow: Boolean? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class SurfaceScopeOverride(
    val materialMode: String? = null,
    val cornerScale: Float? = null,
    val alpha: Int? = null,
    val shadowElevation: Int? = null,
    val blurRadiusDp: Float? = null,
    val borderAlpha: Int? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
) {
    fun isEmpty(): Boolean =
        materialMode == null &&
            cornerScale == null &&
            alpha == null &&
            shadowElevation == null &&
            blurRadiusDp == null &&
            borderAlpha == null &&
            unknownFields.isEmpty()
}

data class SurfaceScopesParams(
    val topBar: SurfaceScopeOverride? = null,
    val bottomBar: SurfaceScopeOverride? = null,
    val bookshelfCard: SurfaceScopeOverride? = null,
    val dialog: SurfaceScopeOverride? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

data class LayoutParams(
    val navigationPreset: String? = null,
    val sidebarGravity: String? = null,
    val topBarFloatingChrome: Boolean? = null,
    val defaultTopBarShowSearch: Boolean? = null,
    val floatingBottomBarHideSearch: Boolean? = null,
    val topBarExpandFilters: Boolean? = null,
    val topBarHideFilterToggle: Boolean? = null,
    val densityScale: Float? = null,
    val fontScale: Int? = null,
    val discoveryPageLayout: Int? = null,
    val rssSourceLayout: Int? = null,
    val homepageLayoutMode: Int? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)

object CoverSlotApplyMode {
    const val RANDOM = "RANDOM"
    const val SEQUENCE = "SEQUENCE"
    const val MIXED = "MIXED"
    const val FALLBACK_ONLY = "FALLBACK_ONLY"

    fun normalize(raw: String?): String {
        return when (raw?.trim()?.uppercase(Locale.ROOT)) {
            SEQUENCE -> SEQUENCE
            MIXED -> MIXED
            FALLBACK_ONLY, "FALLBACK" -> FALLBACK_ONLY
            RANDOM -> RANDOM
            else -> RANDOM
        }
    }
}

object CoverSlotApplyTarget {
    const val GROUPS = "groups"
    const val BOOKS = "books"
    const val BOTH = "both"

    fun normalize(raw: String?, defaultValue: String = BOOKS): String {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            GROUPS, "group", "folder", "folders" -> GROUPS
            BOOKS, "book" -> BOOKS
            BOTH, "all" -> BOTH
            else -> defaultValue
        }
    }
}

data class CoverSlotPackagePayload(
    val covers: List<String> = emptyList(),
    val applyMode: String? = null,
    val applyTarget: String? = null,
    val dayDefaultCoverPath: String? = null,
    val nightDefaultCoverPath: String? = null,
    val dayGroupCoverPath: String? = null,
    val nightGroupCoverPath: String? = null,
    val groupCoverPath: String? = null,
    val unknownFields: Map<String, JsonElement> = emptyMap()
)
