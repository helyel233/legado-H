package io.legado.app.help.config.pkg

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.legado.app.help.config.RedAssetPackage
import io.legado.app.utils.FileUtils
import io.legado.app.utils.isSameOrSubFileOf
import io.legado.app.utils.normalizeFileName
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

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
        manifest.coverSlot?.let { root.add("coverSlot", encodeCoverSlot(it)) }
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
        val coverSlot = root.objOrNull("coverSlot")?.let(::decodeCoverSlot)
        val known = setOf(
            "schemaVersion", "kind", "id", "name", "updatedAt",
            "basePackageId", "contentHash", "art", "param", "coverSlot"
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
            coverSlot = coverSlot,
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
        manifest.coverSlot?.let { canonical.add("coverSlot", encodeCoverSlot(it)) }
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

        fun hashAssetRefs(refs: Map<String, String?>) {
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
            hashAssetRefs(refs)
        }

        manifest.coverSlot?.let { coverSlot ->
            val refs = linkedMapOf<String, String?>()
            coverSlot.covers.sorted().forEachIndexed { idx, v -> refs["coverSlot.cover.$idx"] = v }
            refs["coverSlot.dayDefaultCoverPath"] = coverSlot.dayDefaultCoverPath
            refs["coverSlot.nightDefaultCoverPath"] = coverSlot.nightDefaultCoverPath
            refs["coverSlot.groupCoverPath"] = coverSlot.groupCoverPath
            hashAssetRefs(refs)
        }

        if ((manifest.art != null || manifest.coverSlot != null) && packageDir != null && packageDir.isDirectory) {
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

    /**
     * Collects all local/relative asset paths referenced by [manifest] (excluding remote `http(s)` URLs).
     */
    fun collectAssetPaths(manifest: UnifiedPackageManifest): List<String> {
        val paths = linkedSetOf<String>()
        fun addLocal(raw: String?) {
            val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return
            if (!trimmed.startsWith("http", ignoreCase = true)) {
                paths.add(trimmed)
            }
        }
        manifest.art?.let { art ->
            listOf(art.dayWallpapers, art.nightWallpapers).forEach { w ->
                if (w != null) {
                    addLocal(w.main)
                    addLocal(w.topBar)
                    addLocal(w.bottomBar)
                    addLocal(w.sidebar)
                    addLocal(w.bookInfo)
                    addLocal(w.panel)
                    addLocal(w.welcome)
                }
            }
            listOf(art.dayTypography, art.nightTypography).forEach { t ->
                if (t != null) {
                    addLocal(t.uiFontPath)
                    addLocal(t.titleFontPath)
                }
            }
            art.icons.values.forEach(::addLocal)
            art.covers.forEach(::addLocal)
            addLocal(art.defaultCoverPath)
        }
        manifest.coverSlot?.let { slot ->
            slot.covers.forEach(::addLocal)
            addLocal(slot.dayDefaultCoverPath)
            addLocal(slot.nightDefaultCoverPath)
            addLocal(slot.groupCoverPath)
        }
        return paths.toList()
    }

    /**
     * Validates a [PackageKind.COVER_SLOT] manifest and optionally verifies local asset paths
     * exist and stay confined inside [packageDir] (or resolve via [assetResolver]).
     */
    fun validateCoverSlotPackage(
        manifest: UnifiedPackageManifest,
        packageDir: File? = null,
        assetResolver: ((String) -> File?)? = null
    ): List<String> {
        val errors = mutableListOf<String>()
        if (manifest.kind != PackageKind.COVER_SLOT) {
            errors += "Invalid kind: expected cover_slot, got ${manifest.kind.id}"
        }
        val slot = manifest.coverSlot
        if (slot == null) {
            errors += "Missing coverSlot payload"
            return errors
        }
        val rawMode = slot.applyMode?.trim()?.takeIf { it.isNotBlank() }
        if (rawMode != null) {
            val allowed = setOf(
                CoverSlotApplyMode.RANDOM,
                CoverSlotApplyMode.SEQUENCE,
                CoverSlotApplyMode.MIXED,
                CoverSlotApplyMode.FALLBACK_ONLY,
                "FALLBACK"
            )
            if (rawMode.uppercase(Locale.ROOT) !in allowed) {
                errors += "Unsupported coverSlot applyMode: $rawMode"
            }
        }
        val rawTarget = slot.applyTarget?.trim()?.takeIf { it.isNotBlank() }
        if (rawTarget != null) {
            val allowedTargets = setOf(
                CoverSlotApplyTarget.GROUPS,
                CoverSlotApplyTarget.BOOKS,
                CoverSlotApplyTarget.BOTH,
                "group",
                "folder",
                "folders",
                "book",
                "all"
            )
            if (rawTarget.lowercase(Locale.ROOT) !in allowedTargets) {
                errors += "Unsupported coverSlot applyTarget: $rawTarget"
            }
        }
        collectAssetPaths(manifest).forEach { rel ->
            if (!isSafeRelativePackagePath(rel)) {
                errors += "Unsafe asset path: $rel"
                return@forEach
            }
            if (assetResolver != null || packageDir != null) {
                val resolved = assetResolver?.invoke(rel) ?: packageDir?.let { dir ->
                    File(dir, rel).takeIf { it.isSameOrSubFileOf(dir) && it.isFile }
                }
                if (resolved == null || !resolved.isFile) {
                    errors += "Missing cover asset: $rel"
                }
            }
        }
        return errors
    }

    /**
     * Detects the [PackageKind] of a JSON, unified ZIP, or legacy `.red`/`.zip` cover collection archive.
     */
    fun detectArchivePackageKind(file: File, tempDir: File): PackageKind? {
        if (!file.isFile) return null
        val isJson = runCatching {
            val buf = ByteArray(16)
            val count = file.inputStream().use { it.read(buf) }
            count > 0 && String(buf, 0, count, Charsets.UTF_8).trimStart().startsWith("{")
        }.getOrDefault(false)
        if (isJson) {
            return runCatching { decode(file.readText()).kind }.getOrNull()
        }
        val (zipFile, isTempZip) = unwrapRedOrZip(file, tempDir) ?: return null
        return try {
            ZipFile(zipFile).use { zip ->
                val manifestEntry = zip.entries().asSequence().firstOrNull { entry ->
                    !entry.isDirectory &&
                        (entry.name == UnifiedPackageManifest.MANIFEST_FILE_NAME ||
                            entry.name.endsWith("/${UnifiedPackageManifest.MANIFEST_FILE_NAME}"))
                }
                if (manifestEntry != null) {
                    val text = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.readText()
                    }
                    return runCatching { decode(text).kind }.getOrNull()
                }
            }
            if (RedAssetPackage.classifyZip(zipFile) == RedAssetPackage.Kind.CoverCollection) {
                PackageKind.COVER_SLOT
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            if (isTempZip) zipFile.delete()
        }
    }

    /**
     * Extracts and losslessly converts a legacy `.red` / `.zip` CoverCollection archive
     * (`collection.json` + `images/` or pure cover image zip) into a `PackageKind.COVER_SLOT`
     * directory (`manifest.json` + `assets/`).
     */
    fun convertLegacyCoverCollectionArchive(
        archiveFile: File,
        targetDir: File,
        tempDir: File,
        fallbackName: String? = null,
        packageId: String? = null
    ): UnifiedPackageManifest {
        val (zipFile, isTempZip) = unwrapRedOrZip(archiveFile, tempDir)
            ?: throw IllegalArgumentException("Unsupported archive format: ${archiveFile.name}")
        val unzipDir = File(tempDir, "legacy_cover_${System.currentTimeMillis()}_${UUID.randomUUID()}")
        unzipDir.mkdirs()
        return try {
            PackageIoKernel.extractZipSafe(zipFile, unzipDir)
            val resolvedFallbackName = fallbackName?.trim()?.takeIf { it.isNotBlank() }
                ?: archiveFile.nameWithoutExtension
                    .removeSuffix(".red")
                    .removeSuffix(".zip")
                    .ifBlank { "导入封面包" }
            val manifest = convertLegacyCoverCollectionDir(
                sourceDir = unzipDir,
                targetDir = targetDir,
                fallbackName = resolvedFallbackName,
                packageId = packageId
            )
            PackageIoKernel.writeTextAtomic(
                File(targetDir, UnifiedPackageManifest.MANIFEST_FILE_NAME),
                encode(manifest)
            )
            manifest
        } finally {
            if (isTempZip) zipFile.delete()
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    /**
     * Losslessly converts an extracted legacy CoverCollection directory (`collection.json` + `images/`)
     * into `PackageKind.COVER_SLOT` (`assets/` + [UnifiedPackageManifest]).
     */
    fun convertLegacyCoverCollectionDir(
        sourceDir: File,
        targetDir: File,
        fallbackName: String = "导入封面包",
        packageId: String? = null
    ): UnifiedPackageManifest {
        require(sourceDir.isDirectory) { "Legacy cover directory does not exist: ${sourceDir.absolutePath}" }
        val collectionFile = sourceDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "collection.json" }
        val packageRoot = collectionFile?.parentFile ?: sourceDir
        val collectionObj = collectionFile?.let { file ->
            runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
        }
        val resolvedName = collectionObj?.stringOrNull("name")?.takeIf { it.isNotBlank() }
            ?: fallbackName.ifBlank { "导入封面包" }
        val resolvedMode = CoverSlotApplyMode.normalize(collectionObj?.stringOrNull("mode"))
        val updatedAt = collectionObj?.longOrNull("updatedAt")?.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        fun isImageFile(f: File): Boolean = f.isFile && f.extension.lowercase(Locale.ROOT) in imageExtensions

        val candidateFiles = mutableListOf<File>()
        val imagesElem = collectionObj?.get("images")
        if (imagesElem != null && imagesElem.isJsonArray) {
            imagesElem.asJsonArray.forEach { elem ->
                if (!elem.isJsonPrimitive || !elem.asJsonPrimitive.isString) return@forEach
                val raw = elem.asString.trim()
                if (raw.isBlank()) return@forEach
                val direct = if (!File(raw).isAbsolute) {
                    File(packageRoot, raw).takeIf { it.isSameOrSubFileOf(sourceDir) && isImageFile(it) }
                } else null
                val fallbackByName = direct ?: run {
                    val fileName = File(raw).name
                    File(packageRoot, "images/$fileName").takeIf { it.isSameOrSubFileOf(sourceDir) && isImageFile(it) }
                        ?: File(packageRoot, fileName).takeIf { it.isSameOrSubFileOf(sourceDir) && isImageFile(it) }
                }
                if (fallbackByName != null) {
                    candidateFiles.add(fallbackByName)
                }
            }
        }
        if (candidateFiles.isEmpty()) {
            sourceDir.walkTopDown()
                .filter(::isImageFile)
                .sortedBy { it.relativeTo(sourceDir).path.replace('\\', '/') }
                .forEach { candidateFiles.add(it) }
        }
        require(candidateFiles.isNotEmpty()) { "封面包内未找到有效图片文件" }

        targetDir.mkdirs()
        val assetsDir = File(targetDir, "assets").apply { mkdirs() }
        val packagedCovers = mutableListOf<String>()
        candidateFiles.distinctBy { it.canonicalPath }.forEachIndexed { index, src ->
            val rawStem = src.nameWithoutExtension.normalizeFileName().ifBlank { "cover_${index + 1}" }
            val ext = src.extension.lowercase(Locale.ROOT).ifBlank { "jpg" }
            var candidate = File(assetsDir, "$rawStem.$ext")
            var suffix = 1
            while (candidate.exists()) {
                candidate = File(assetsDir, "${rawStem}_$suffix.$ext")
                suffix++
            }
            src.copyTo(candidate, overwrite = true)
            packagedCovers.add("assets/${candidate.name}")
        }

        val payload = CoverSlotPackagePayload(
            covers = packagedCovers,
            applyMode = resolvedMode
        )
        val draft = UnifiedPackageManifest(
            kind = PackageKind.COVER_SLOT,
            id = packageId?.takeIf { it.isNotBlank() } ?: "cover_slot_${UUID.randomUUID()}",
            name = resolvedName,
            updatedAt = updatedAt,
            coverSlot = payload
        )
        return draft.copy(
            contentHash = computeContentHash(draft, targetDir)
        )
    }

    private fun unwrapRedOrZip(file: File, tempDir: File): Pair<File, Boolean>? {
        val header = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(8)
                val n = input.read(buf)
                if (n >= 2) buf.copyOf(n) else null
            }
        }.getOrNull() ?: return null
        if (header.size >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            return file to false
        }
        tempDir.mkdirs()
        val tempZip = RedAssetPackage.zipPayload(file, tempDir) ?: return null
        return tempZip to true
    }

    private fun isSafeRelativePackagePath(path: String): Boolean {
        val normalized = path.trim().replace('\\', '/')
        if (normalized.isBlank() || '\u0000' in normalized) return false
        if (normalized.startsWith('/')) return false
        if (Regex("^[A-Za-z]:.*").matches(normalized)) return false
        val segments = normalized.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
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

    private fun encodeCoverSlot(slot: CoverSlotPackagePayload): JsonObject = JsonObject().apply {
        if (slot.covers.isNotEmpty()) {
            val arr = JsonArray()
            slot.covers.forEach { path ->
                if (path.isNotBlank()) arr.add(path)
            }
            if (arr.size() > 0) add("covers", arr)
        }
        putStr("applyMode", slot.applyMode)
        putStr("applyTarget", slot.applyTarget)
        putStr("dayDefaultCoverPath", slot.dayDefaultCoverPath)
        putStr("nightDefaultCoverPath", slot.nightDefaultCoverPath)
        putStr("groupCoverPath", slot.groupCoverPath)
        appendUnknownFields(this, slot.unknownFields)
    }

    private fun decodeCoverSlot(obj: JsonObject): CoverSlotPackagePayload {
        val coversList = mutableListOf<String>()
        val coversElem = obj.get("covers")
        if (coversElem != null && coversElem.isJsonArray) {
            coversElem.asJsonArray.forEach { elem ->
                if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                    elem.asString.trim().takeIf { it.isNotBlank() }?.let(coversList::add)
                }
            }
        }
        val known = setOf(
            "covers",
            "applyMode",
            "applyTarget",
            "dayDefaultCoverPath",
            "nightDefaultCoverPath",
            "groupCoverPath"
        )
        return CoverSlotPackagePayload(
            covers = coversList,
            applyMode = obj.stringOrNull("applyMode")?.takeIf { it.isNotBlank() },
            applyTarget = obj.stringOrNull("applyTarget")?.takeIf { it.isNotBlank() },
            dayDefaultCoverPath = obj.stringOrNull("dayDefaultCoverPath")?.takeIf { it.isNotBlank() },
            nightDefaultCoverPath = obj.stringOrNull("nightDefaultCoverPath")?.takeIf { it.isNotBlank() },
            groupCoverPath = obj.stringOrNull("groupCoverPath")?.takeIf { it.isNotBlank() },
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
