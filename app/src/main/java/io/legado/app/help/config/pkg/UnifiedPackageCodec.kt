package io.legado.app.help.config.pkg

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.legado.app.utils.isSameOrSubFileOf
import java.io.File
import java.security.MessageDigest

/**
 * Schema-versioned codec for [UnifiedPackageManifest] with full unknown-field round-trip
 * preservation across top-level, art, param, scope overrides, and layout blocks.
 */
object UnifiedPackageCodec {

    const val CONTENT_HASH_PREFIX = "v2:"

    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    fun isCurrentContentHash(hash: String?): Boolean {
        return hash?.startsWith(CONTENT_HASH_PREFIX) == true
    }

    fun encode(manifest: UnifiedPackageManifest): String {
        val root = JsonObject()
        root.addProperty("schemaVersion", manifest.schemaVersion)
        root.addProperty("kind", manifest.kind.id)
        root.addProperty("id", manifest.id)
        root.addProperty("name", manifest.name)
        root.addProperty("updatedAt", manifest.updatedAt)
        manifest.basePackageId?.takeIf { it.isNotBlank() }?.let { root.addProperty("basePackageId", it) }
        manifest.contentHash?.takeIf { it.isNotBlank() }?.let { root.addProperty("contentHash", it) }
        manifest.art?.let { root.add("art", encodeArt(it)) }
        manifest.param?.let { root.add("param", encodeParam(it)) }
        appendUnknownFields(root, manifest.unknownFields)
        return prettyGson.toJson(root)
    }

    fun decode(json: String): UnifiedPackageManifest {
        val root = JsonParser.parseString(json).asJsonObject
        val schemaVersion = root.intOrNull("schemaVersion") ?: UnifiedPackageManifest.CURRENT_SCHEMA_VERSION
        val kind = PackageKind.fromId(root.stringOrNull("kind"))
            ?: throw IllegalArgumentException("Unsupported package kind: ${root.stringOrNull("kind")}")
        val id = root.stringOrNull("id").orEmpty()
        val name = root.stringOrNull("name").orEmpty()
        val updatedAt = root.longOrNull("updatedAt") ?: 0L
        val basePackageId = root.stringOrNull("basePackageId")?.takeIf { it.isNotBlank() }
        val contentHash = root.stringOrNull("contentHash")?.takeIf { it.isNotBlank() }
        val art = root.objOrNull("art")?.let(::decodeArt)
        val param = root.objOrNull("param")?.let(::decodeParam)
        val known = setOf(
            "schemaVersion", "kind", "id", "name", "updatedAt",
            "basePackageId", "contentHash", "art", "param"
        )
        return UnifiedPackageManifest(
            schemaVersion = schemaVersion,
            kind = kind,
            id = id,
            name = name,
            updatedAt = updatedAt,
            basePackageId = basePackageId,
            contentHash = contentHash,
            art = art,
            param = param,
            unknownFields = collectUnknownFields(root, known)
        )
    }

    /**
     * Computes a deterministic SHA-256 content fingerprint ignoring volatile identity metadata
     * (`id`, `name`, `updatedAt`, `basePackageId`, `contentHash`), hashing both structured
     * payload fields and local/CoW-resolved asset file bytes.
     */
    fun computeContentHash(
        manifest: UnifiedPackageManifest,
        packageDir: File? = null,
        assetResolver: ((String) -> File?)? = null
    ): String {
        val canonical = JsonObject()
        canonical.addProperty("kind", manifest.kind.id)
        manifest.art?.let { canonical.add("art", encodeArt(it)) }
        manifest.param?.let { canonical.add("param", encodeParam(it)) }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(canonical.toString().toByteArray(Charsets.UTF_8))

        val hashedCanonicalPaths = hashSetOf<String>()
        val buf = ByteArray(8192)
        fun updateFile(tag: String, file: File) {
            if (!file.isFile) return
            md.update(0)
            md.update(tag.toByteArray(Charsets.UTF_8))
            md.update(0)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            runCatching { file.canonicalPath }.getOrNull()?.let(hashedCanonicalPaths::add)
        }

        manifest.art?.let { art ->
            val refs = linkedMapOf<String, String?>()
            fun collectWallpapers(prefix: String, w: ArtWallpapersSpec?) {
                if (w == null) return
                refs["$prefix.main"] = w.main
                refs["$prefix.topBar"] = w.topBar
                refs["$prefix.bottomBar"] = w.bottomBar
                refs["$prefix.sidebar"] = w.sidebar
                refs["$prefix.bookInfo"] = w.bookInfo
                refs["$prefix.panel"] = w.panel
                refs["$prefix.welcome"] = w.welcome
            }
            fun collectTypography(prefix: String, t: ArtTypographySpec?) {
                if (t == null) return
                refs["$prefix.uiFont"] = t.uiFontPath
                refs["$prefix.titleFont"] = t.titleFontPath
            }
            collectWallpapers("day", art.dayWallpapers)
            collectWallpapers("night", art.nightWallpapers)
            collectTypography("day", art.dayTypography)
            collectTypography("night", art.nightTypography)
            art.icons.toSortedMap().forEach { (k, v) -> refs["icon.$k"] = v }
            art.covers.sorted().forEachIndexed { idx, v -> refs["cover.$idx"] = v }
            refs["defaultCoverPath"] = art.defaultCoverPath

            refs.forEach { (slot, rawPath) ->
                val trimmed = rawPath?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                if (trimmed.startsWith("http", ignoreCase = true)) return@forEach
                val resolved = assetResolver?.invoke(trimmed) ?: packageDir?.let { dir ->
                    val candidate = File(dir, trimmed)
                    candidate.takeIf { it.isSameOrSubFileOf(dir) && it.isFile }
                }
                if (resolved != null && resolved.isFile) {
                    updateFile("slot:$slot", resolved)
                }
            }
        }

        if (manifest.art != null && packageDir != null && packageDir.isDirectory) {
            packageDir.walkTopDown()
                .onEnter { dir -> dir == packageDir || !dir.name.startsWith(".") }
                .filter { file ->
                    file.isFile &&
                        file.name != UnifiedPackageManifest.MANIFEST_FILE_NAME &&
                        !file.name.startsWith(".")
                }
                .map { file ->
                    file.relativeTo(packageDir).path.replace('\\', '/') to file
                }
                .sortedBy { it.first }
                .forEach { (relPath, file) ->
                    val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
                    if (canonicalPath == null || canonicalPath !in hashedCanonicalPaths) {
                        updateFile("file:$relPath", file)
                    }
                }
        }

        return CONTENT_HASH_PREFIX + md.digest().joinToString("") { "%02x".format(it) }
    }

    fun encodeScopeOverride(override: SurfaceScopeOverride): String {
        return prettyGson.toJson(encodeScopeOverrideObject(override))
    }

    fun decodeScopeOverride(json: String?): SurfaceScopeOverride? {
        val trimmed = json?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val obj = JsonParser.parseString(trimmed).asJsonObject
            decodeScopeOverrideObject(obj)
        }.getOrNull()
    }

    private fun encodeArt(art: ArtPackagePayload): JsonObject {
        val obj = JsonObject()
        val palettes = JsonObject()
        art.dayPalette?.let { palettes.add("day", encodePalette(it)) }
        art.nightPalette?.let { palettes.add("night", encodePalette(it)) }
        if (palettes.size() > 0) obj.add("palettes", palettes)

        val wallpapers = JsonObject()
        art.dayWallpapers?.let { wallpapers.add("day", encodeWallpapers(it)) }
        art.nightWallpapers?.let { wallpapers.add("night", encodeWallpapers(it)) }
        if (wallpapers.size() > 0) obj.add("wallpapers", wallpapers)

        val typography = JsonObject()
        art.dayTypography?.let { typography.add("day", encodeTypography(it)) }
        art.nightTypography?.let { typography.add("night", encodeTypography(it)) }
        if (typography.size() > 0) obj.add("typography", typography)

        if (art.icons.isNotEmpty()) {
            val iconsObj = JsonObject()
            art.icons.forEach { (k, v) -> iconsObj.addProperty(k, v) }
            obj.add("icons", iconsObj)
        }
        if (art.covers.isNotEmpty()) {
            val coversArr = JsonArray()
            art.covers.forEach { coversArr.add(it) }
            obj.add("covers", coversArr)
        }
        art.defaultCoverPath?.takeIf { it.isNotBlank() }?.let { obj.addProperty("defaultCoverPath", it) }
        obj.add("mounts", encodeMounts(art.mounts))
        appendUnknownFields(obj, art.unknownFields)
        return obj
    }

    private fun decodeArt(obj: JsonObject): ArtPackagePayload {
        val palettes = obj.objOrNull("palettes")
        val wallpapers = obj.objOrNull("wallpapers")
        val typography = obj.objOrNull("typography")
        val iconsObj = obj.objOrNull("icons")
        val iconsMap = linkedMapOf<String, String>()
        iconsObj?.entrySet()?.forEach { (k, v) ->
            if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                iconsMap[k] = v.asString
            }
        }
        val coversList = mutableListOf<String>()
        val coversElem = obj.get("covers")
        if (coversElem != null && coversElem.isJsonArray) {
            coversElem.asJsonArray.forEach { elem ->
                if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                    coversList.add(elem.asString)
                }
            }
        }
        val known = setOf("palettes", "wallpapers", "typography", "icons", "covers", "defaultCoverPath", "mounts")
        return ArtPackagePayload(
            dayPalette = palettes?.objOrNull("day")?.let(::decodePalette),
            nightPalette = palettes?.objOrNull("night")?.let(::decodePalette),
            dayWallpapers = wallpapers?.objOrNull("day")?.let(::decodeWallpapers),
            nightWallpapers = wallpapers?.objOrNull("night")?.let(::decodeWallpapers),
            dayTypography = typography?.objOrNull("day")?.let(::decodeTypography),
            nightTypography = typography?.objOrNull("night")?.let(::decodeTypography),
            icons = iconsMap,
            covers = coversList,
            defaultCoverPath = obj.stringOrNull("defaultCoverPath"),
            mounts = obj.objOrNull("mounts")?.let(::decodeMounts) ?: ArtAssetMounts(),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodePalette(p: ArtPaletteSpec): JsonObject = JsonObject().apply {
        putStr("primaryColor", p.primaryColor)
        putStr("accentColor", p.accentColor)
        putStr("backgroundColor", p.backgroundColor)
        putStr("bottomBackground", p.bottomBackground)
        putStr("cardColor", p.cardColor)
        putStr("mutedColor", p.mutedColor)
        putStr("searchFieldBackgroundColor", p.searchFieldBackgroundColor)
        putStr("tabBackgroundColor", p.tabBackgroundColor)
        putStr("shelfColor", p.shelfColor)
        putStr("panelBorderColor", p.panelBorderColor)
        putStr("uiFontColor", p.uiFontColor)
        putStr("titleFontColor", p.titleFontColor)
        appendUnknownFields(this, p.unknownFields)
    }

    private fun decodePalette(obj: JsonObject): ArtPaletteSpec {
        val known = setOf(
            "primaryColor", "accentColor", "backgroundColor", "bottomBackground",
            "cardColor", "mutedColor", "searchFieldBackgroundColor", "tabBackgroundColor",
            "shelfColor", "panelBorderColor", "uiFontColor", "titleFontColor"
        )
        return ArtPaletteSpec(
            primaryColor = obj.stringOrNull("primaryColor"),
            accentColor = obj.stringOrNull("accentColor"),
            backgroundColor = obj.stringOrNull("backgroundColor"),
            bottomBackground = obj.stringOrNull("bottomBackground"),
            cardColor = obj.stringOrNull("cardColor"),
            mutedColor = obj.stringOrNull("mutedColor"),
            searchFieldBackgroundColor = obj.stringOrNull("searchFieldBackgroundColor"),
            tabBackgroundColor = obj.stringOrNull("tabBackgroundColor"),
            shelfColor = obj.stringOrNull("shelfColor"),
            panelBorderColor = obj.stringOrNull("panelBorderColor"),
            uiFontColor = obj.stringOrNull("uiFontColor"),
            titleFontColor = obj.stringOrNull("titleFontColor"),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeWallpapers(w: ArtWallpapersSpec): JsonObject = JsonObject().apply {
        putStr("main", w.main)
        putStr("mainCrop", w.mainCrop)
        putStr("topBar", w.topBar)
        putFloat("topBarCropLeft", w.topBarCropLeft)
        putFloat("topBarCropTop", w.topBarCropTop)
        putFloat("topBarCropRight", w.topBarCropRight)
        putFloat("topBarCropBottom", w.topBarCropBottom)
        putInt("topBarAlpha", w.topBarAlpha)
        putStr("bottomBar", w.bottomBar)
        putStr("sidebar", w.sidebar)
        putStr("bookInfo", w.bookInfo)
        putStr("panel", w.panel)
        putStr("panelScaleType", w.panelScaleType)
        putStr("welcome", w.welcome)
        appendUnknownFields(this, w.unknownFields)
    }

    private fun decodeWallpapers(obj: JsonObject): ArtWallpapersSpec {
        val known = setOf(
            "main", "mainCrop", "topBar", "topBarCropLeft", "topBarCropTop",
            "topBarCropRight", "topBarCropBottom", "topBarAlpha", "bottomBar",
            "sidebar", "bookInfo", "panel", "panelScaleType", "welcome"
        )
        return ArtWallpapersSpec(
            main = obj.stringOrNull("main"),
            mainCrop = obj.stringOrNull("mainCrop"),
            topBar = obj.stringOrNull("topBar"),
            topBarCropLeft = obj.floatOrNull("topBarCropLeft"),
            topBarCropTop = obj.floatOrNull("topBarCropTop"),
            topBarCropRight = obj.floatOrNull("topBarCropRight"),
            topBarCropBottom = obj.floatOrNull("topBarCropBottom"),
            topBarAlpha = obj.intOrNull("topBarAlpha"),
            bottomBar = obj.stringOrNull("bottomBar"),
            sidebar = obj.stringOrNull("sidebar"),
            bookInfo = obj.stringOrNull("bookInfo"),
            panel = obj.stringOrNull("panel"),
            panelScaleType = obj.stringOrNull("panelScaleType"),
            welcome = obj.stringOrNull("welcome"),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeTypography(t: ArtTypographySpec): JsonObject = JsonObject().apply {
        putStr("uiFontPath", t.uiFontPath)
        putStr("titleFontPath", t.titleFontPath)
        appendUnknownFields(this, t.unknownFields)
    }

    private fun decodeTypography(obj: JsonObject): ArtTypographySpec {
        val known = setOf("uiFontPath", "titleFontPath")
        return ArtTypographySpec(
            uiFontPath = obj.stringOrNull("uiFontPath"),
            titleFontPath = obj.stringOrNull("titleFontPath"),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeMounts(m: ArtAssetMounts): JsonObject = JsonObject().apply {
        putStr("dayIconSlotId", m.dayIconSlotId)
        putStr("nightIconSlotId", m.nightIconSlotId)
        putStr("dayCoverSlotId", m.dayCoverSlotId)
        putStr("nightCoverSlotId", m.nightCoverSlotId)
        appendUnknownFields(this, m.unknownFields)
    }

    private fun decodeMounts(obj: JsonObject): ArtAssetMounts {
        val known = setOf("dayIconSlotId", "nightIconSlotId", "dayCoverSlotId", "nightCoverSlotId")
        return ArtAssetMounts(
            dayIconSlotId = obj.stringOrNull("dayIconSlotId"),
            nightIconSlotId = obj.stringOrNull("nightIconSlotId"),
            dayCoverSlotId = obj.stringOrNull("dayCoverSlotId"),
            nightCoverSlotId = obj.stringOrNull("nightCoverSlotId"),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeParam(param: ParamPackagePayload): JsonObject = JsonObject().apply {
        param.surfaceGlobal?.let { add("surfaceGlobal", encodeSurfaceGlobal(it)) }
        param.surfaceScopes?.let { add("surfaceScopes", encodeSurfaceScopes(it)) }
        param.layout?.let { add("layout", encodeLayout(it)) }
        appendUnknownFields(this, param.unknownFields)
    }

    private fun decodeParam(obj: JsonObject): ParamPackagePayload {
        val known = setOf("surfaceGlobal", "surfaceScopes", "layout")
        return ParamPackagePayload(
            surfaceGlobal = obj.objOrNull("surfaceGlobal")?.let(::decodeSurfaceGlobal),
            surfaceScopes = obj.objOrNull("surfaceScopes")?.let(::decodeSurfaceScopes),
            layout = obj.objOrNull("layout")?.let(::decodeLayout),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeSurfaceGlobal(g: SurfaceGlobalParams): JsonObject = JsonObject().apply {
        putStr("materialMode", g.materialMode)
        putInt("frostedLevel", g.frostedLevel)
        putInt("liquidLevel", g.liquidLevel)
        putInt("blurScale", g.blurScale)
        putInt("shellScale", g.shellScale)
        putInt("tintScale", g.tintScale)
        putInt("strokeScale", g.strokeScale)
        putStr("strokeColor", g.strokeColor)
        putBool("presetMatched", g.presetMatched)
        putBool("hardwareBlur", g.hardwareBlur)
        putBool("animatedSolid", g.animatedSolid)
        putFloat("cornerBaseScale", g.cornerBaseScale)
        putInt("surfaceAlpha", g.surfaceAlpha)
        putInt("dialogAlpha", g.dialogAlpha)
        putInt("panelBorderAlpha", g.panelBorderAlpha)
        putInt("cardShadow", g.cardShadow)
        putFloat("cardBackgroundBlur", g.cardBackgroundBlur)
        putInt("wallpaperBlurDay", g.wallpaperBlurDay)
        putInt("wallpaperBlurNight", g.wallpaperBlurNight)
        putBool("uiCornerSearchFollow", g.uiCornerSearchFollow)
        putBool("uiCornerReplyFollow", g.uiCornerReplyFollow)
        appendUnknownFields(this, g.unknownFields)
    }

    private fun decodeSurfaceGlobal(obj: JsonObject): SurfaceGlobalParams {
        val known = setOf(
            "materialMode", "frostedLevel", "liquidLevel", "blurScale", "shellScale",
            "tintScale", "strokeScale", "strokeColor", "presetMatched", "hardwareBlur",
            "animatedSolid", "cornerBaseScale", "surfaceAlpha", "dialogAlpha",
            "panelBorderAlpha", "cardShadow", "cardBackgroundBlur", "wallpaperBlurDay",
            "wallpaperBlurNight", "uiCornerSearchFollow", "uiCornerReplyFollow"
        )
        return SurfaceGlobalParams(
            materialMode = obj.stringOrNull("materialMode"),
            frostedLevel = obj.intOrNull("frostedLevel"),
            liquidLevel = obj.intOrNull("liquidLevel"),
            blurScale = obj.intOrNull("blurScale"),
            shellScale = obj.intOrNull("shellScale"),
            tintScale = obj.intOrNull("tintScale"),
            strokeScale = obj.intOrNull("strokeScale"),
            strokeColor = obj.stringOrNull("strokeColor"),
            presetMatched = obj.boolOrNull("presetMatched"),
            hardwareBlur = obj.boolOrNull("hardwareBlur"),
            animatedSolid = obj.boolOrNull("animatedSolid"),
            cornerBaseScale = obj.floatOrNull("cornerBaseScale"),
            surfaceAlpha = obj.intOrNull("surfaceAlpha"),
            dialogAlpha = obj.intOrNull("dialogAlpha"),
            panelBorderAlpha = obj.intOrNull("panelBorderAlpha"),
            cardShadow = obj.intOrNull("cardShadow"),
            cardBackgroundBlur = obj.floatOrNull("cardBackgroundBlur"),
            wallpaperBlurDay = obj.intOrNull("wallpaperBlurDay"),
            wallpaperBlurNight = obj.intOrNull("wallpaperBlurNight"),
            uiCornerSearchFollow = obj.boolOrNull("uiCornerSearchFollow"),
            uiCornerReplyFollow = obj.boolOrNull("uiCornerReplyFollow"),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeSurfaceScopes(s: SurfaceScopesParams): JsonObject = JsonObject().apply {
        s.topBar?.let { add(UiScope.TOP_BAR.id, encodeScopeOverrideObject(it)) }
        s.bottomBar?.let { add(UiScope.BOTTOM_BAR.id, encodeScopeOverrideObject(it)) }
        s.bookshelfCard?.let { add(UiScope.BOOKSHELF_CARD.id, encodeScopeOverrideObject(it)) }
        s.dialog?.let { add(UiScope.DIALOG.id, encodeScopeOverrideObject(it)) }
        appendUnknownFields(this, s.unknownFields)
    }

    private fun decodeSurfaceScopes(obj: JsonObject): SurfaceScopesParams {
        val known = setOf(
            UiScope.TOP_BAR.id,
            UiScope.BOTTOM_BAR.id,
            UiScope.BOOKSHELF_CARD.id,
            UiScope.DIALOG.id
        )
        return SurfaceScopesParams(
            topBar = obj.objOrNull(UiScope.TOP_BAR.id)?.let(::decodeScopeOverrideObject),
            bottomBar = obj.objOrNull(UiScope.BOTTOM_BAR.id)?.let(::decodeScopeOverrideObject),
            bookshelfCard = obj.objOrNull(UiScope.BOOKSHELF_CARD.id)?.let(::decodeScopeOverrideObject),
            dialog = obj.objOrNull(UiScope.DIALOG.id)?.let(::decodeScopeOverrideObject),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeScopeOverrideObject(o: SurfaceScopeOverride): JsonObject = JsonObject().apply {
        putStr("materialMode", o.materialMode)
        putFloat("cornerScale", o.cornerScale)
        putInt("alpha", o.alpha)
        putInt("shadowElevation", o.shadowElevation)
        putFloat("blurRadiusDp", o.blurRadiusDp)
        putInt("borderAlpha", o.borderAlpha)
        appendUnknownFields(this, o.unknownFields)
    }

    private fun decodeScopeOverrideObject(obj: JsonObject): SurfaceScopeOverride {
        val known = setOf(
            "materialMode", "cornerScale", "alpha",
            "shadowElevation", "blurRadiusDp", "borderAlpha"
        )
        return SurfaceScopeOverride(
            materialMode = obj.stringOrNull("materialMode"),
            cornerScale = obj.floatOrNull("cornerScale"),
            alpha = obj.intOrNull("alpha"),
            shadowElevation = obj.intOrNull("shadowElevation"),
            blurRadiusDp = obj.floatOrNull("blurRadiusDp"),
            borderAlpha = obj.intOrNull("borderAlpha"),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun encodeLayout(l: LayoutParams): JsonObject = JsonObject().apply {
        putStr("navigationPreset", l.navigationPreset)
        putStr("sidebarGravity", l.sidebarGravity)
        putBool("topBarFloatingChrome", l.topBarFloatingChrome)
        putBool("defaultTopBarShowSearch", l.defaultTopBarShowSearch)
        putBool("floatingBottomBarHideSearch", l.floatingBottomBarHideSearch)
        putBool("topBarExpandFilters", l.topBarExpandFilters)
        putBool("topBarHideFilterToggle", l.topBarHideFilterToggle)
        putFloat("densityScale", l.densityScale)
        putInt("fontScale", l.fontScale)
        putInt("discoveryPageLayout", l.discoveryPageLayout)
        putInt("rssSourceLayout", l.rssSourceLayout)
        putInt("homepageLayoutMode", l.homepageLayoutMode)
        appendUnknownFields(this, l.unknownFields)
    }

    private fun decodeLayout(obj: JsonObject): LayoutParams {
        val known = setOf(
            "navigationPreset", "sidebarGravity", "topBarFloatingChrome",
            "defaultTopBarShowSearch", "floatingBottomBarHideSearch",
            "topBarExpandFilters", "topBarHideFilterToggle",
            "densityScale", "fontScale", "discoveryPageLayout",
            "rssSourceLayout", "homepageLayoutMode"
        )
        return LayoutParams(
            navigationPreset = obj.stringOrNull("navigationPreset"),
            sidebarGravity = obj.stringOrNull("sidebarGravity"),
            topBarFloatingChrome = obj.boolOrNull("topBarFloatingChrome"),
            defaultTopBarShowSearch = obj.boolOrNull("defaultTopBarShowSearch"),
            floatingBottomBarHideSearch = obj.boolOrNull("floatingBottomBarHideSearch"),
            topBarExpandFilters = obj.boolOrNull("topBarExpandFilters"),
            topBarHideFilterToggle = obj.boolOrNull("topBarHideFilterToggle"),
            densityScale = obj.floatOrNull("densityScale"),
            fontScale = obj.intOrNull("fontScale"),
            discoveryPageLayout = obj.intOrNull("discoveryPageLayout"),
            rssSourceLayout = obj.intOrNull("rssSourceLayout"),
            homepageLayoutMode = obj.intOrNull("homepageLayoutMode"),
            unknownFields = collectUnknownFields(obj, known)
        )
    }

    private fun collectUnknownFields(obj: JsonObject, known: Set<String>): Map<String, JsonElement> {
        val unknown = linkedMapOf<String, JsonElement>()
        obj.entrySet().forEach { (key, value) ->
            if (key !in known && !value.isJsonNull) {
                unknown[key] = value.deepCopy()
            }
        }
        return unknown
    }

    private fun appendUnknownFields(target: JsonObject, unknownFields: Map<String, JsonElement>) {
        unknownFields.forEach { (key, value) ->
            if (!target.has(key)) {
                target.add(key, value.deepCopy())
            }
        }
    }

    private fun JsonObject.objOrNull(key: String): JsonObject? {
        val element = get(key) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = get(key) ?: return null
        return runCatching { if (element.isJsonPrimitive) element.asInt else null }.getOrNull()
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val element = get(key) ?: return null
        return runCatching { if (element.isJsonPrimitive) element.asLong else null }.getOrNull()
    }

    private fun JsonObject.floatOrNull(key: String): Float? {
        val element = get(key) ?: return null
        return runCatching { if (element.isJsonPrimitive) element.asFloat else null }.getOrNull()
    }

    private fun JsonObject.boolOrNull(key: String): Boolean? {
        val element = get(key) ?: return null
        return runCatching {
            val prim = element as? JsonPrimitive ?: return@runCatching null
            if (prim.isBoolean) prim.asBoolean else null
        }.getOrNull()
    }

    private fun JsonObject.putStr(key: String, value: String?) {
        if (value != null) addProperty(key, value)
    }

    private fun JsonObject.putInt(key: String, value: Int?) {
        if (value != null) addProperty(key, value)
    }

    private fun JsonObject.putFloat(key: String, value: Float?) {
        if (value != null) addProperty(key, value)
    }

    private fun JsonObject.putBool(key: String, value: Boolean?) {
        if (value != null) addProperty(key, value)
    }
}
