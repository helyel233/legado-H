package io.legado.app.ui.config

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.cnCompare
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale

/**
 * 字体文件二进制格式枚举。
 */
enum class FontFormat(
    val extension: String,
    val displayLabel: String,
    val isNativeSupported: Boolean
) {
    TRUETYPE("ttf", "TrueType", true),
    OPENTYPE_CFF("otf", "OpenType", true),
    TRUETYPE_COLLECTION("ttc", "TrueType Collection", true),
    WOFF("woff", "WOFF", false),
    WOFF2("woff2", "WOFF2", false)
}

/**
 * 字体校验与 SFNT 元数据解析结果。
 */
@Immutable
data class FontInspectionResult(
    val isValid: Boolean,
    val isNativeSupported: Boolean,
    val format: FontFormat? = null,
    val suggestedExtension: String? = null,
    val familyName: String? = null,
    val fileSizeBytes: Long? = null,
    val formattedSize: String? = null,
    val errorReason: String? = null
)

/**
 * 字体文件头魔数校验、扩展名推断与 SFNT `name` 表解析器（纯 JVM 可测）。
 */
object FontFileValidator {

    private const val MAX_TABLES_GUARD = 256
    private const val MAX_NAME_TABLE_BYTES = 1024 * 1024
    private const val MAX_STREAM_SCAN_BYTES = 16 * 1024 * 1024

    val supportedExtensions: Set<String> = setOf("ttf", "otf", "ttc")

    fun isSupportedExtension(nameOrPath: String): Boolean {
        val ext = nameOrPath.trim().substringAfterLast('.', "").lowercase(Locale.US)
        return ext in supportedExtensions
    }

    fun formatFileSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
            safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun detectFormat(header: ByteArray, length: Int = header.size): FontFormat? {
        if (length < 4 || header.size < 4) return null
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF
        val magic = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        return when (magic) {
            0x00010000, 0x74727565 -> FontFormat.TRUETYPE // 0x00010000 or "true"
            0x4F54544F -> FontFormat.OPENTYPE_CFF // "OTTO"
            0x74746366 -> FontFormat.TRUETYPE_COLLECTION // "ttcf"
            0x774F4646 -> FontFormat.WOFF // "wOFF"
            0x774F4632 -> FontFormat.WOFF2 // "wOF2"
            else -> null
        }
    }

    fun inferExtension(header: ByteArray, fallbackFileName: String? = null): String? {
        detectFormat(header)?.let { return it.extension }
        val fallbackExt = fallbackFileName
            ?.trim()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it in supportedExtensions }
        return fallbackExt
    }

    fun inspectBytes(
        bytes: ByteArray,
        fallbackFileName: String? = null,
        sizeOverrideBytes: Long? = null
    ): FontInspectionResult {
        val reportedSize = sizeOverrideBytes ?: bytes.size.toLong()
        val formattedSize = formatFileSize(reportedSize)
        if (bytes.size < 4) {
            return FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                fileSizeBytes = reportedSize,
                formattedSize = formattedSize,
                errorReason = "字体文件为空或内容不完整（不足 4 字节）"
            )
        }
        val format = detectFormat(bytes)
            ?: return FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                suggestedExtension = inferExtension(bytes, fallbackFileName),
                fileSizeBytes = reportedSize,
                formattedSize = formattedSize,
                errorReason = buildInvalidHeaderReason(bytes)
            )
        if (!format.isNativeSupported) {
            return FontInspectionResult(
                isValid = true,
                isNativeSupported = false,
                format = format,
                suggestedExtension = format.extension,
                fileSizeBytes = reportedSize,
                formattedSize = formattedSize,
                errorReason = "检测到 ${format.displayLabel} 网页字体，Android 界面仅支持 .ttf/.otf/.ttc"
            )
        }
        val familyName = runCatching { extractFamilyNameFromBytes(bytes, format) }.getOrNull()
        return FontInspectionResult(
            isValid = true,
            isNativeSupported = true,
            format = format,
            suggestedExtension = format.extension,
            familyName = familyName,
            fileSizeBytes = reportedSize,
            formattedSize = formattedSize,
            errorReason = null
        )
    }

    fun inspectFile(file: File): FontInspectionResult {
        if (!file.exists() || !file.isFile) {
            return FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                errorReason = "字体文件不存在：${file.name}"
            )
        }
        val fileLength = file.length()
        val formattedSize = formatFileSize(fileLength)
        if (fileLength < 4L) {
            return FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                fileSizeBytes = fileLength,
                formattedSize = formattedSize,
                errorReason = "字体文件为空或已损坏（不足 4 字节）"
            )
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(16.coerceAtMost(fileLength.toInt()))
                raf.readFully(header)
                val format = detectFormat(header)
                    ?: return@use FontInspectionResult(
                        isValid = false,
                        isNativeSupported = false,
                        fileSizeBytes = fileLength,
                        formattedSize = formattedSize,
                        errorReason = buildInvalidHeaderReason(header)
                    )
                if (!format.isNativeSupported) {
                    return@use FontInspectionResult(
                        isValid = true,
                        isNativeSupported = false,
                        format = format,
                        suggestedExtension = format.extension,
                        fileSizeBytes = fileLength,
                        formattedSize = formattedSize,
                        errorReason = "检测到 ${format.displayLabel} 网页字体，Android 界面仅支持 .ttf/.otf/.ttc"
                    )
                }
                val familyName = runCatching {
                    extractFamilyNameFromRandomAccess(raf, fileLength, format)
                }.getOrNull()
                FontInspectionResult(
                    isValid = true,
                    isNativeSupported = true,
                    format = format,
                    suggestedExtension = format.extension,
                    familyName = familyName,
                    fileSizeBytes = fileLength,
                    formattedSize = formattedSize,
                    errorReason = null
                )
            }
        }.getOrElse { err ->
            FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                fileSizeBytes = fileLength,
                formattedSize = formattedSize,
                errorReason = "读取字体失败：${err.message ?: "未知错误"}"
            )
        }
    }

    fun inspectStream(
        input: InputStream,
        fallbackFileName: String? = null,
        knownSizeBytes: Long? = null
    ): FontInspectionResult {
        return runCatching {
            val header = readExactUpTo(input, 16)
            val fallbackSize = knownSizeBytes ?: header.size.toLong()
            if (header.size < 4) {
                return@runCatching FontInspectionResult(
                    isValid = false,
                    isNativeSupported = false,
                    fileSizeBytes = knownSizeBytes,
                    formattedSize = knownSizeBytes?.let(::formatFileSize),
                    errorReason = "字体文件为空或内容不完整（不足 4 字节）"
                )
            }
            val format = detectFormat(header)
                ?: return@runCatching FontInspectionResult(
                    isValid = false,
                    isNativeSupported = false,
                    fileSizeBytes = knownSizeBytes,
                    formattedSize = knownSizeBytes?.let(::formatFileSize),
                    errorReason = buildInvalidHeaderReason(header)
                )
            if (!format.isNativeSupported) {
                return@runCatching FontInspectionResult(
                    isValid = true,
                    isNativeSupported = false,
                    format = format,
                    suggestedExtension = format.extension,
                    fileSizeBytes = knownSizeBytes,
                    formattedSize = knownSizeBytes?.let(::formatFileSize),
                    errorReason = "检测到 ${format.displayLabel} 网页字体，Android 界面仅支持 .ttf/.otf/.ttc"
                )
            }
            val familyName = runCatching {
                extractFamilyNameFromStream(header, input, format, knownSizeBytes)
            }.getOrNull()
            FontInspectionResult(
                isValid = true,
                isNativeSupported = true,
                format = format,
                suggestedExtension = format.extension,
                familyName = familyName,
                fileSizeBytes = knownSizeBytes ?: fallbackSize,
                formattedSize = (knownSizeBytes ?: fallbackSize).let(::formatFileSize),
                errorReason = null
            )
        }.getOrElse { err ->
            FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                fileSizeBytes = knownSizeBytes,
                formattedSize = knownSizeBytes?.let(::formatFileSize),
                errorReason = "校验字体流失败：${err.message ?: "未知错误"}"
            )
        }
    }

    fun inspectUri(context: Context, uri: Uri): FontInspectionResult {
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        val displayName = doc?.name ?: uri.lastPathSegment?.substringAfterLast('/')
        val knownSize = doc?.length()?.takeIf { it > 0L }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                inspectStream(stream, displayName, knownSize)
            } ?: FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                fileSizeBytes = knownSize,
                formattedSize = knownSize?.let(::formatFileSize),
                errorReason = "无法打开字体文件流"
            )
        }.getOrElse { err ->
            FontInspectionResult(
                isValid = false,
                isNativeSupported = false,
                fileSizeBytes = knownSize,
                formattedSize = knownSize?.let(::formatFileSize),
                errorReason = "读取字体 URI 失败：${err.message ?: "权限不足或文件已失效"}"
            )
        }
    }

    fun isNativeRenderableFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(4)
                raf.readFully(header)
                detectFormat(header)?.isNativeSupported == true
            }
        }.getOrDefault(false)
    }

    fun isNativeRenderableUri(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = readExactUpTo(stream, 4)
                detectFormat(header)?.isNativeSupported == true
            } ?: false
        }.getOrDefault(false)
    }

    private fun buildInvalidHeaderReason(header: ByteArray): String {
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF
        val hex = String.format(Locale.US, "%02X%02X%02X%02X", b0, b1, b2, b3)
        val guessed = when {
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> "PNG 图片数据"
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> "JPEG 图片数据"
            b0 == 0x50 && b1 == 0x4B && (b2 == 0x03 || b2 == 0x05) -> "ZIP 压缩包数据"
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38 -> "GIF 图片数据"
            b0 == '{'.code || b0 == '['.code -> "JSON 文本"
            b0 == '<'.code -> "XML/HTML 文本"
            else -> null
        }
        return if (guessed != null) {
            "格式无效：检测到 $guessed，非 TTF/OTF/TTC 字体文件"
        } else {
            "格式无效：文件头 0x$hex 不属于 TTF/OTF/TTC 字体"
        }
    }

    private fun extractFamilyNameFromBytes(bytes: ByteArray, format: FontFormat): String? {
        val sfntOffset = resolveFirstSfntOffset(bytes, bytes.size, format) ?: return null
        if (sfntOffset + 12 > bytes.size) return null
        val numTables = readUInt16(bytes, sfntOffset + 4).coerceAtMost(MAX_TABLES_GUARD)
        val directoryEnd = sfntOffset + 12 + numTables * 16
        if (directoryEnd > bytes.size) return null
        for (i in 0 until numTables) {
            val recOffset = sfntOffset + 12 + i * 16
            if (isNameTag(bytes, recOffset)) {
                val tableOffset = readUInt32(bytes, recOffset + 8)
                val tableLength = readUInt32(bytes, recOffset + 12)
                if (tableOffset < 0 || tableLength <= 6 || tableOffset + tableLength > bytes.size) {
                    return null
                }
                return parseNameTable(
                    tableBytes = bytes,
                    tableStart = tableOffset.toInt(),
                    tableLength = tableLength.toInt().coerceAtMost(MAX_NAME_TABLE_BYTES)
                )
            }
        }
        return null
    }

    private fun extractFamilyNameFromRandomAccess(
        raf: RandomAccessFile,
        fileLength: Long,
        format: FontFormat
    ): String? {
        val sfntOffset: Long = if (format == FontFormat.TRUETYPE_COLLECTION) {
            if (fileLength < 16L) return null
            raf.seek(8L)
            val buf = ByteArray(8)
            raf.readFully(buf)
            val numFonts = readUInt32(buf, 0)
            if (numFonts <= 0L) return null
            val firstOffset = readUInt32(buf, 4)
            if (firstOffset < 0L || firstOffset + 12L > fileLength) return null
            firstOffset
        } else {
            0L
        }
        raf.seek(sfntOffset)
        val offsetHeader = ByteArray(12)
        raf.readFully(offsetHeader)
        val numTables = readUInt16(offsetHeader, 4).coerceAtMost(MAX_TABLES_GUARD)
        val dirBytesLen = numTables * 16
        if (sfntOffset + 12L + dirBytesLen > fileLength) return null
        val dirBytes = ByteArray(dirBytesLen)
        raf.readFully(dirBytes)
        for (i in 0 until numTables) {
            val rec = i * 16
            if (isNameTag(dirBytes, rec)) {
                val tableOffset = readUInt32(dirBytes, rec + 8)
                val tableLength = readUInt32(dirBytes, rec + 12)
                if (tableOffset < 0L || tableLength <= 6L || tableOffset + tableLength > fileLength) {
                    return null
                }
                val readLen = tableLength.coerceAtMost(MAX_NAME_TABLE_BYTES.toLong()).toInt()
                raf.seek(tableOffset)
                val nameBytes = ByteArray(readLen)
                raf.readFully(nameBytes)
                return parseNameTable(nameBytes, 0, readLen)
            }
        }
        return null
    }

    private fun extractFamilyNameFromStream(
        initialHeader: ByteArray,
        input: InputStream,
        format: FontFormat,
        knownSizeBytes: Long?
    ): String? {
        if (knownSizeBytes != null && knownSizeBytes > MAX_STREAM_SCAN_BYTES) {
            // 超大 SAF 字体流仅解析位于文件前部的 OffsetTable + name 表，避免整包读入内存
            var position = initialHeader.size.toLong()
            val sfntOffset: Long = if (format == FontFormat.TRUETYPE_COLLECTION) {
                if (initialHeader.size < 16) return null
                val numFonts = readUInt32(initialHeader, 8)
                if (numFonts <= 0L) return null
                readUInt32(initialHeader, 12)
            } else {
                0L
            }
            if (sfntOffset < 0L || sfntOffset > MAX_STREAM_SCAN_BYTES) return null
            if (sfntOffset > position) {
                if (!skipFully(input, sfntOffset - position)) return null
                position = sfntOffset
            }
            val offsetHeader: ByteArray = if (sfntOffset == 0L) {
                initialHeader.copyOfRange(0, 12)
            } else {
                val h = readExactUpTo(input, 12)
                if (h.size < 12) return null
                position += 12L
                h
            }
            val numTables = readUInt16(offsetHeader, 4).coerceAtMost(MAX_TABLES_GUARD)
            val alreadyInInitial = if (sfntOffset == 0L) initialHeader.size - 12 else 0
            val dirBytesNeeded = numTables * 16
            val dirBytes = ByteArray(dirBytesNeeded)
            if (alreadyInInitial > 0) {
                val copyCount = alreadyInInitial.coerceAtMost(dirBytesNeeded)
                System.arraycopy(initialHeader, 12, dirBytes, 0, copyCount)
                if (copyCount < dirBytesNeeded) {
                    val rest = readExactUpTo(input, dirBytesNeeded - copyCount)
                    if (rest.size < dirBytesNeeded - copyCount) return null
                    System.arraycopy(rest, 0, dirBytes, copyCount, rest.size)
                    position += rest.size
                }
            } else {
                val read = readExactUpTo(input, dirBytesNeeded)
                if (read.size < dirBytesNeeded) return null
                System.arraycopy(read, 0, dirBytes, 0, dirBytesNeeded)
                position += read.size
            }
            for (i in 0 until numTables) {
                val rec = i * 16
                if (isNameTag(dirBytes, rec)) {
                    val tableOffset = readUInt32(dirBytes, rec + 8)
                    val tableLength = readUInt32(dirBytes, rec + 12)
                    if (tableOffset < position || tableLength <= 6L || tableOffset > MAX_STREAM_SCAN_BYTES) {
                        return null
                    }
                    if (!skipFully(input, tableOffset - position)) return null
                    val readLen = tableLength.coerceAtMost(MAX_NAME_TABLE_BYTES.toLong()).toInt()
                    val nameBytes = readExactUpTo(input, readLen)
                    return parseNameTable(nameBytes, 0, nameBytes.size)
                }
            }
            return null
        }

        val out = ByteArrayOutputStream()
        out.write(initialHeader)
        val buf = ByteArray(8192)
        var total = initialHeader.size
        while (total < MAX_STREAM_SCAN_BYTES) {
            val n = input.read(buf, 0, buf.size.coerceAtMost(MAX_STREAM_SCAN_BYTES - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return extractFamilyNameFromBytes(out.toByteArray(), format)
    }

    private fun resolveFirstSfntOffset(bytes: ByteArray, length: Int, format: FontFormat): Int? {
        if (format != FontFormat.TRUETYPE_COLLECTION) return 0
        if (length < 16) return null
        val numFonts = readUInt32(bytes, 8)
        if (numFonts <= 0L) return null
        val firstOffset = readUInt32(bytes, 12)
        if (firstOffset < 0L || firstOffset >= length) return null
        return firstOffset.toInt()
    }

    private fun parseNameTable(tableBytes: ByteArray, tableStart: Int, tableLength: Int): String? {
        if (tableLength < 6 || tableStart + tableLength > tableBytes.size) return null
        val count = readUInt16(tableBytes, tableStart + 2)
        val stringOffset = readUInt16(tableBytes, tableStart + 4)
        val recordsEnd = tableStart + 6 + count * 12
        if (stringOffset > tableLength || recordsEnd > tableStart + tableLength) {
            // 部分精简字体 count 略偏大时按可用边界截断
        }
        val maxRecords = ((tableLength - 6) / 12).coerceAtLeast(0).coerceAtMost(count)
        var bestScore = -1
        var bestName: String? = null
        for (i in 0 until maxRecords) {
            val rec = tableStart + 6 + i * 12
            val platformId = readUInt16(tableBytes, rec)
            val encodingId = readUInt16(tableBytes, rec + 2)
            val languageId = readUInt16(tableBytes, rec + 4)
            val nameId = readUInt16(tableBytes, rec + 6)
            if (nameId != 16 && nameId != 1 && nameId != 4) continue
            val strLen = readUInt16(tableBytes, rec + 8)
            val strOff = readUInt16(tableBytes, rec + 10)
            if (strLen <= 0) continue
            val absStart = tableStart + stringOffset + strOff
            if (absStart < tableStart || absStart + strLen > tableStart + tableLength) continue
            val charset: Charset = when {
                platformId == 3 && (encodingId == 1 || encodingId == 10 || encodingId == 0) -> Charsets.UTF_16BE
                platformId == 0 -> Charsets.UTF_16BE
                platformId == 1 && encodingId == 0 -> Charsets.ISO_8859_1
                else -> continue
            }
            val decoded = String(tableBytes, absStart, strLen, charset)
                .replace("\u0000", "")
                .trim()
            if (decoded.isEmpty()) continue
            val nameIdScore = when (nameId) {
                16 -> 300
                1 -> 200
                else -> 100
            }
            val platformScore = when {
                platformId == 3 && (encodingId == 1 || encodingId == 10) -> 30
                platformId == 0 -> 20
                else -> 10
            }
            val langScore = when {
                platformId == 3 && languageId == 0x0804 -> 5
                platformId == 3 && (languageId == 0x0404 || languageId == 0x0C04) -> 4
                platformId == 3 && languageId == 0x0409 -> 3
                languageId == 0 -> 2
                else -> 1
            }
            val totalScore = nameIdScore + platformScore + langScore
            if (totalScore > bestScore) {
                bestScore = totalScore
                bestName = decoded
            }
        }
        return bestName
    }

    private fun isNameTag(bytes: ByteArray, offset: Int): Boolean {
        return bytes[offset] == 'n'.code.toByte() &&
            bytes[offset + 1] == 'a'.code.toByte() &&
            bytes[offset + 2] == 'm'.code.toByte() &&
            bytes[offset + 3] == 'e'.code.toByte()
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun readExactUpTo(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n <= 0) break
            offset += n
        }
        return if (offset == count) buf else buf.copyOf(offset)
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return false
                remaining--
            }
        }
        return true
    }
}

/**
 * 字体来源类型。
 */
enum class ArtFontSourceKind {
    DEFAULT,
    SYSTEM,
    FONT_LIBRARY_REF,
    PACKAGE_EMBEDDED,
    LOCAL_FILE,
    INVALID
}

/**
 * 字体库扫描条目。
 */
@Immutable
data class FontLibraryEntry(
    val fileName: String,
    val atFontRef: String,
    val localPath: String?,
    val contentUri: String?,
    val sizeBytes: Long?,
    val originLabel: String
)

/**
 * 协议级字体路径解析目标。
 */
sealed class ResolvedFontTarget {
    data class System(val normalizedRef: String, val displayLabel: String) : ResolvedFontTarget()
    data class LocalFile(val file: File) : ResolvedFontTarget()
    data class ContentUri(val uri: Uri, val displayName: String? = null, val sizeBytes: Long? = null) : ResolvedFontTarget()
}

/**
 * 统一解析 `system:*`、`@font:<name>`、包内相对路径（含 CoW baseDir 回退）、`content://` 与 `file://`。
 */
object FontProtocolResolver {

    fun normalizeSystemProtocol(raw: String): String? {
        return when (raw.trim().lowercase(Locale.US)) {
            "system" -> "system"
            "system:sans", "system:sans-serif" -> "system:sans"
            "system:serif" -> "system:serif"
            "system:mono", "system:monospace" -> "system:mono"
            else -> null
        }
    }

    fun systemDisplayLabel(normalizedRef: String): String {
        return when (normalizedRef) {
            "system:sans" -> "系统无衬线 (Sans)"
            "system:serif" -> "系统衬线 (Serif)"
            "system:mono" -> "系统等宽 (Mono)"
            else -> "系统默认字体"
        }
    }

    fun systemTypeface(normalizedRef: String, defaultFallback: Typeface = Typeface.SANS_SERIF): Typeface {
        return when (normalizedRef) {
            "system:sans" -> Typeface.SANS_SERIF
            "system:serif" -> Typeface.SERIF
            "system:mono" -> Typeface.MONOSPACE
            else -> defaultFallback
        }
    }

    fun extractAtFontName(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("@font:", ignoreCase = true)) return null
        return trimmed.substring(6).trim().takeIf { it.isNotEmpty() }
    }

    fun resolveAtFontTarget(context: Context, fontName: String): ResolvedFontTarget? {
        val want = fontName.trim()
        if (want.isEmpty()) return null
        val wantBase = want.substringBeforeLast('.')

        // 1. 应用私有字体目录：externalFiles/font
        runCatching {
            val appFontDir = File(FileUtils.getPath(context.externalFiles, "font"))
            matchLocalFileInDir(appFontDir, want, wantBase)?.let {
                return ResolvedFontTarget.LocalFile(it)
            }
        }

        // 2. 用户自定义字体目录（PreferKey.fontFolder）
        val customFolder = runCatching { context.getPrefString(PreferKey.fontFolder) }.getOrNull()
        if (!customFolder.isNullOrBlank()) {
            val normalizedFolder = if (customFolder.startsWith("file://", ignoreCase = true)) {
                runCatching { Uri.parse(customFolder).path }.getOrNull() ?: customFolder
            } else {
                customFolder
            }
            if (normalizedFolder.isContentScheme()) {
                runCatching {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(normalizedFolder))
                    val files = tree?.listFiles().orEmpty()
                    val exact = files.firstOrNull {
                        it.isFile && it.name?.equals(want, ignoreCase = true) == true
                    }
                    val byBase = exact ?: files.firstOrNull { doc ->
                        val name = doc.name ?: return@firstOrNull false
                        doc.isFile && FontFileValidator.isSupportedExtension(name) &&
                            name.substringBeforeLast('.').equals(wantBase, ignoreCase = true)
                    }
                    if (byBase != null) {
                        return ResolvedFontTarget.ContentUri(
                            uri = byBase.uri,
                            displayName = byBase.name,
                            sizeBytes = byBase.length().takeIf { it > 0L }
                        )
                    }
                }
            } else {
                runCatching {
                    matchLocalFileInDir(File(normalizedFolder), want, wantBase)?.let {
                        return ResolvedFontTarget.LocalFile(it)
                    }
                }
            }
        }
        return null
    }

    fun resolveTarget(
        context: Context,
        path: String,
        packageDir: File? = null,
        baseDir: File? = null
    ): ResolvedFontTarget? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null

        normalizeSystemProtocol(trimmed)?.let { sys ->
            return ResolvedFontTarget.System(sys, systemDisplayLabel(sys))
        }

        extractAtFontName(trimmed)?.let { name ->
            return resolveAtFontTarget(context, name)
        }

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            val uri = Uri.parse(trimmed)
            val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            return ResolvedFontTarget.ContentUri(
                uri = uri,
                displayName = doc?.name ?: uri.lastPathSegment?.substringAfterLast('/'),
                sizeBytes = doc?.length()?.takeIf { it > 0L }
            )
        }

        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val filePath = Uri.parse(trimmed).path ?: return null
            val file = File(filePath)
            return if (file.isFile) ResolvedFontTarget.LocalFile(file) else null
        }

        val direct = File(trimmed)
        if (direct.isAbsolute) {
            return if (direct.isFile) ResolvedFontTarget.LocalFile(direct) else null
        }

        // 相对路径（如 assets/day_ui_font.ttf）：依次查找 packageDir 与 CoW baseDir
        if (packageDir != null) {
            resolveSafeSubFile(packageDir, trimmed)?.let {
                return ResolvedFontTarget.LocalFile(it)
            }
        }
        if (baseDir != null) {
            resolveSafeSubFile(baseDir, trimmed)?.let {
                return ResolvedFontTarget.LocalFile(it)
            }
        }
        return null
    }

    fun scanFontLibrary(context: Context): List<FontLibraryEntry> {
        val dedup = linkedMapOf<String, FontLibraryEntry>()

        // 1. 应用私有字体目录
        runCatching {
            val appFontDir = File(FileUtils.getPath(context.externalFiles, "font"))
            if (appFontDir.isDirectory) {
                appFontDir.listFiles().orEmpty()
                    .filter { it.isFile && FontFileValidator.isSupportedExtension(it.name) }
                    .forEach { file ->
                        dedup.putIfAbsent(
                            file.name.lowercase(Locale.US),
                            FontLibraryEntry(
                                fileName = file.name,
                                atFontRef = "@font:${file.name}",
                                localPath = file.absolutePath,
                                contentUri = null,
                                sizeBytes = file.length(),
                                originLabel = "应用字体库"
                            )
                        )
                    }
            }
        }

        // 2. 用户自定义字体目录
        val customFolder = runCatching { context.getPrefString(PreferKey.fontFolder) }.getOrNull()
        if (!customFolder.isNullOrBlank()) {
            val folder = if (customFolder.startsWith("file://", ignoreCase = true)) {
                runCatching { Uri.parse(customFolder).path }.getOrNull() ?: customFolder
            } else {
                customFolder
            }
            if (folder.isContentScheme()) {
                runCatching {
                    val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder))
                    tree?.listFiles().orEmpty().forEach { doc ->
                        val name = doc.name ?: return@forEach
                        if (!doc.isFile || !FontFileValidator.isSupportedExtension(name)) return@forEach
                        dedup.putIfAbsent(
                            name.lowercase(Locale.US),
                            FontLibraryEntry(
                                fileName = name,
                                atFontRef = "@font:$name",
                                localPath = null,
                                contentUri = doc.uri.toString(),
                                sizeBytes = doc.length().takeIf { it > 0L },
                                originLabel = "自定义字体目录"
                            )
                        )
                    }
                }
            } else {
                runCatching {
                    val dir = File(folder)
                    if (dir.isDirectory && dir.canRead()) {
                        dir.listFiles().orEmpty()
                            .filter { it.isFile && FontFileValidator.isSupportedExtension(it.name) }
                            .forEach { file ->
                                dedup.putIfAbsent(
                                    file.name.lowercase(Locale.US),
                                    FontLibraryEntry(
                                        fileName = file.name,
                                        atFontRef = "@font:${file.name}",
                                        localPath = file.absolutePath,
                                        contentUri = null,
                                        sizeBytes = file.length(),
                                        originLabel = "自定义字体目录"
                                    )
                                )
                            }
                    }
                }
            }
        }

        return dedup.values.sortedWith { a, b -> a.fileName.cnCompare(b.fileName) }
    }

    /**
     * 将字体库条目解析为可随包复制嵌入的本地绝对路径；若为 SAF `content://` 则先校验并暂存到缓存目录。
     */
    fun resolveEmbeddableLocalPath(context: Context, entry: FontLibraryEntry): String? {
        entry.localPath?.let { path ->
            val file = File(path)
            if (file.isFile && FontFileValidator.isNativeRenderableFile(file)) {
                return file.absolutePath
            }
        }
        val uriString = entry.contentUri ?: return null
        val uri = Uri.parse(uriString)
        val real = runCatching { RealPathUtil.getPath(context, uri) }.getOrNull()
        if (!real.isNullOrBlank()) {
            val file = File(real)
            if (file.isFile && FontFileValidator.isNativeRenderableFile(file)) {
                return file.absolutePath
            }
        }
        return runCatching {
            val inspection = FontFileValidator.inspectUri(context, uri)
            if (!inspection.isValid || !inspection.isNativeSupported) return@runCatching null
            val ext = inspection.suggestedExtension
                ?: entry.fileName.substringAfterLast('.', "").lowercase(Locale.US).takeIf {
                    it in FontFileValidator.supportedExtensions
                }
                ?: "ttf"
            val baseName = entry.fileName.substringBeforeLast('.').ifBlank { "font" }
            val stageDir = File(context.cacheDir, "art_font_stage").apply { mkdirs() }
            val dst = File(stageDir, "$baseName.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            dst.takeIf { it.isFile && FontFileValidator.isNativeRenderableFile(it) }?.absolutePath
        }.getOrNull()
    }

    private fun matchLocalFileInDir(dir: File, want: String, wantBase: String): File? {
        if (!dir.isDirectory || !dir.canRead()) return null
        val direct = File(dir, want)
        if (direct.isFile) return direct
        val files = dir.listFiles().orEmpty()
        files.firstOrNull { it.isFile && it.name.equals(want, ignoreCase = true) }?.let { return it }
        return files.firstOrNull { file ->
            file.isFile && FontFileValidator.isSupportedExtension(file.name) &&
                file.nameWithoutExtension.equals(wantBase, ignoreCase = true)
        }
    }

    private fun resolveSafeSubFile(root: File, relativePath: String): File? {
        val candidate = File(root, relativePath)
        val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val candCanonical = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        val insideRoot = candCanonical == rootCanonical ||
            candCanonical.startsWith("$rootCanonical${File.separator}")
        return candidate.takeIf { insideRoot && it.isFile }
    }
}

/**
 * 预览槽位 UI 状态（含异步解析后的 Typeface、SFNT Family Name 与来源徽标）。
 */
@Immutable
data class FontPreviewLoadState(
    val isLoading: Boolean = false,
    val typeface: Typeface? = null,
    val familyName: String? = null,
    val displayName: String = "系统默认字体",
    val sourceKind: ArtFontSourceKind = ArtFontSourceKind.DEFAULT,
    val sourceBadge: String = "默认跟随",
    val fileSizeBytes: Long? = null,
    val formattedSize: String? = null,
    val isValid: Boolean = true,
    val errorReason: String? = null
)

/**
 * 双层内存隔离的异步字体预览引擎：
 * 1. 候选列表层：有界 `LruCache(maxSize = 10)`，通过 `Dispatchers.IO.limitedParallelism(2)` 异步解码；
 * 2. 编辑器固定预览卡层：独立 2 槽固定引用缓存（界面正文 + 标题），不进入列表滑动 LRU，防止快速滑动挤出当前预览字体。
 */
object FontPreviewLoader {

    const val CANDIDATE_LRU_MAX_SIZE = 10
    const val PINNED_SLOT_MAX_SIZE = 2

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val candidateLru = LruCache<String, Typeface>(CANDIDATE_LRU_MAX_SIZE)
    private val pinnedLock = Any()
    private val pinnedSlots = LinkedHashMap<String, Typeface>(4, 0.75f, true)

    fun buildDefaultState(path: String): FontPreviewLoadState {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return FontPreviewLoadState(
                isLoading = false,
                typeface = null,
                familyName = "系统默认字体",
                displayName = "系统默认字体",
                sourceKind = ArtFontSourceKind.DEFAULT,
                sourceBadge = "默认跟随",
                isValid = true
            )
        }
        val sys = FontProtocolResolver.normalizeSystemProtocol(trimmed)
        if (sys != null) {
            val label = FontProtocolResolver.systemDisplayLabel(sys)
            return FontPreviewLoadState(
                isLoading = false,
                typeface = resolveSystemTypefaceSafe(sys),
                familyName = label,
                displayName = label,
                sourceKind = ArtFontSourceKind.SYSTEM,
                sourceBadge = "系统预设",
                isValid = true
            )
        }
        val rawName = when {
            trimmed.startsWith("@font:", ignoreCase = true) -> trimmed.substring(6).trim()
            else -> trimmed.substringAfterLast('/').substringAfterLast(File.separator)
        }.ifBlank { trimmed }
        val kind = inferSourceKind(trimmed)
        return FontPreviewLoadState(
            isLoading = true,
            typeface = null,
            familyName = null,
            displayName = rawName,
            sourceKind = kind,
            sourceBadge = badgeForSourceKind(kind),
            isValid = true
        )
    }

    suspend fun loadCandidatePreview(
        context: Context,
        path: String,
        packageDir: File? = null,
        baseDir: File? = null
    ): FontPreviewLoadState {
        return loadInternal(context, path, packageDir, baseDir, usePinnedSlot = false)
    }

    suspend fun loadPinnedPreview(
        context: Context,
        path: String,
        packageDir: File? = null,
        baseDir: File? = null
    ): FontPreviewLoadState {
        return loadInternal(context, path, packageDir, baseDir, usePinnedSlot = true)
    }

    private suspend fun loadInternal(
        context: Context,
        path: String,
        packageDir: File?,
        baseDir: File?,
        usePinnedSlot: Boolean
    ): FontPreviewLoadState {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || FontProtocolResolver.normalizeSystemProtocol(trimmed) != null) {
            return buildDefaultState(trimmed)
        }
        return withContext(decodeDispatcher) {
            val kind = inferSourceKind(trimmed)
            val fallbackName = when {
                trimmed.startsWith("@font:", ignoreCase = true) -> trimmed.substring(6).trim()
                else -> trimmed.substringAfterLast('/').substringAfterLast(File.separator)
            }.ifBlank { trimmed }

            val target = FontProtocolResolver.resolveTarget(context, trimmed, packageDir, baseDir)
                ?: return@withContext FontPreviewLoadState(
                    isLoading = false,
                    typeface = null,
                    familyName = null,
                    displayName = fallbackName,
                    sourceKind = ArtFontSourceKind.INVALID,
                    sourceBadge = "格式无效",
                    isValid = false,
                    errorReason = if (trimmed.startsWith("@font:", ignoreCase = true)) {
                        "字体库中未找到「$fallbackName」"
                    } else {
                        "字体文件不存在或无法访问"
                    }
                )

            when (target) {
                is ResolvedFontTarget.System -> {
                    val label = target.displayLabel
                    FontPreviewLoadState(
                        isLoading = false,
                        typeface = resolveSystemTypefaceSafe(target.normalizedRef),
                        familyName = label,
                        displayName = label,
                        sourceKind = ArtFontSourceKind.SYSTEM,
                        sourceBadge = "系统预设",
                        isValid = true
                    )
                }
                is ResolvedFontTarget.LocalFile -> {
                    val file = target.file
                    val inspection = FontFileValidator.inspectFile(file)
                    if (!inspection.isValid || !inspection.isNativeSupported) {
                        return@withContext FontPreviewLoadState(
                            isLoading = false,
                            typeface = null,
                            familyName = inspection.familyName,
                            displayName = file.name,
                            sourceKind = ArtFontSourceKind.INVALID,
                            sourceBadge = "格式无效",
                            fileSizeBytes = inspection.fileSizeBytes,
                            formattedSize = inspection.formattedSize,
                            isValid = false,
                            errorReason = inspection.errorReason ?: "无效字体文件"
                        )
                    }
                    val stampKey = "${file.absolutePath}#${file.length()}:${file.lastModified()}"
                    val cached = getFromTier(stampKey, usePinnedSlot)
                    val typeface = cached ?: runCatching {
                        Typeface.createFromFile(file)
                    }.getOrNull()?.also { putToTier(stampKey, it, usePinnedSlot) }

                    if (typeface == null) {
                        FontPreviewLoadState(
                            isLoading = false,
                            typeface = null,
                            familyName = inspection.familyName,
                            displayName = file.name,
                            sourceKind = ArtFontSourceKind.INVALID,
                            sourceBadge = "格式无效",
                            fileSizeBytes = inspection.fileSizeBytes,
                            formattedSize = inspection.formattedSize,
                            isValid = false,
                            errorReason = "系统字体引擎解码失败（文件可能已损坏）"
                        )
                    } else {
                        FontPreviewLoadState(
                            isLoading = false,
                            typeface = typeface,
                            familyName = inspection.familyName,
                            displayName = file.name,
                            sourceKind = kind,
                            sourceBadge = badgeForSourceKind(kind),
                            fileSizeBytes = inspection.fileSizeBytes,
                            formattedSize = inspection.formattedSize,
                            isValid = true
                        )
                    }
                }
                is ResolvedFontTarget.ContentUri -> {
                    val inspection = FontFileValidator.inspectUri(context, target.uri)
                    val displayName = target.displayName ?: fallbackName
                    if (!inspection.isValid || !inspection.isNativeSupported) {
                        return@withContext FontPreviewLoadState(
                            isLoading = false,
                            typeface = null,
                            familyName = inspection.familyName,
                            displayName = displayName,
                            sourceKind = ArtFontSourceKind.INVALID,
                            sourceBadge = "格式无效",
                            fileSizeBytes = inspection.fileSizeBytes ?: target.sizeBytes,
                            formattedSize = inspection.formattedSize
                                ?: target.sizeBytes?.let(FontFileValidator::formatFileSize),
                            isValid = false,
                            errorReason = inspection.errorReason ?: "无效字体文件"
                        )
                    }
                    val key = "${target.uri}#${inspection.fileSizeBytes ?: target.sizeBytes ?: 0L}"
                    val cached = getFromTier(key, usePinnedSlot)
                    val typeface = cached ?: decodeContentUriTypeface(context, target.uri)
                        ?.also { putToTier(key, it, usePinnedSlot) }

                    if (typeface == null) {
                        FontPreviewLoadState(
                            isLoading = false,
                            typeface = null,
                            familyName = inspection.familyName,
                            displayName = displayName,
                            sourceKind = ArtFontSourceKind.INVALID,
                            sourceBadge = "格式无效",
                            fileSizeBytes = inspection.fileSizeBytes ?: target.sizeBytes,
                            formattedSize = inspection.formattedSize
                                ?: target.sizeBytes?.let(FontFileValidator::formatFileSize),
                            isValid = false,
                            errorReason = "系统字体引擎解码失败"
                        )
                    } else {
                        FontPreviewLoadState(
                            isLoading = false,
                            typeface = typeface,
                            familyName = inspection.familyName,
                            displayName = displayName,
                            sourceKind = kind,
                            sourceBadge = badgeForSourceKind(kind),
                            fileSizeBytes = inspection.fileSizeBytes ?: target.sizeBytes,
                            formattedSize = inspection.formattedSize
                                ?: target.sizeBytes?.let(FontFileValidator::formatFileSize),
                            isValid = true
                        )
                    }
                }
            }
        }
    }

    private fun decodeContentUriTypeface(context: Context, uri: Uri): Typeface? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    Typeface.Builder(pfd.fileDescriptor).build()
                }
            } else {
                RealPathUtil.getPath(context, uri)?.let { real ->
                    Typeface.createFromFile(real)
                }
            }
        }.getOrNull()
    }

    private fun getFromTier(key: String, usePinnedSlot: Boolean): Typeface? {
        return if (usePinnedSlot) {
            synchronized(pinnedLock) { pinnedSlots[key] }
        } else {
            candidateLru[key]
        }
    }

    private fun putToTier(key: String, typeface: Typeface, usePinnedSlot: Boolean) {
        if (usePinnedSlot) {
            synchronized(pinnedLock) {
                pinnedSlots[key] = typeface
                while (pinnedSlots.size > PINNED_SLOT_MAX_SIZE) {
                    val eldest = pinnedSlots.entries.firstOrNull()?.key ?: break
                    pinnedSlots.remove(eldest)
                }
            }
        } else {
            candidateLru.put(key, typeface)
        }
    }

    private fun inferSourceKind(path: String): ArtFontSourceKind {
        val trimmed = path.trim()
        return when {
            trimmed.isEmpty() -> ArtFontSourceKind.DEFAULT
            FontProtocolResolver.normalizeSystemProtocol(trimmed) != null -> ArtFontSourceKind.SYSTEM
            trimmed.startsWith("@font:", ignoreCase = true) -> ArtFontSourceKind.FONT_LIBRARY_REF
            trimmed.startsWith("assets/", ignoreCase = true) -> ArtFontSourceKind.PACKAGE_EMBEDDED
            else -> ArtFontSourceKind.LOCAL_FILE
        }
    }

    private fun badgeForSourceKind(kind: ArtFontSourceKind): String {
        return when (kind) {
            ArtFontSourceKind.DEFAULT -> "默认跟随"
            ArtFontSourceKind.SYSTEM -> "系统预设"
            ArtFontSourceKind.FONT_LIBRARY_REF -> "引用字体库 @font:"
            ArtFontSourceKind.PACKAGE_EMBEDDED -> "本地嵌入"
            ArtFontSourceKind.LOCAL_FILE -> "本地嵌入"
            ArtFontSourceKind.INVALID -> "格式无效"
        }
    }

    private fun resolveSystemTypefaceSafe(normalizedRef: String): Typeface {
        val fallback = runCatching {
            when (AppConfig.systemTypefaces) {
                1 -> Typeface.SERIF
                2 -> Typeface.MONOSPACE
                else -> Typeface.SANS_SERIF
            }
        }.getOrDefault(Typeface.SANS_SERIF)
        return FontProtocolResolver.systemTypeface(normalizedRef, fallback)
    }

    internal fun putCandidateForTest(key: String, typeface: Typeface) {
        candidateLru.put(key, typeface)
    }

    internal fun getCandidateForTest(key: String): Typeface? = candidateLru[key]

    internal fun candidateCacheSize(): Int = candidateLru.size()

    internal fun candidateSnapshotKeys(): Set<String> = candidateLru.snapshot().keys

    internal fun putPinnedForTest(key: String, typeface: Typeface) {
        putToTier(key, typeface, usePinnedSlot = true)
    }

    internal fun getPinnedForTest(key: String): Typeface? {
        return synchronized(pinnedLock) { pinnedSlots[key] }
    }

    internal fun pinnedCacheSize(): Int {
        return synchronized(pinnedLock) { pinnedSlots.size }
    }

    internal fun clearCachesForTest() {
        candidateLru.evictAll()
        synchronized(pinnedLock) { pinnedSlots.clear() }
    }
}

/**
 * 编辑器预览卡专用的固定槽位异步加载 Hook（最多持有正文 + 标题 2 个固定引用，不进候选列表滑动 LRU）。
 */
@Composable
fun rememberPinnedPreviewTypeface(
    path: String,
    packageDir: File? = null,
    baseDir: File? = null
): State<FontPreviewLoadState> {
    val context = LocalContext.current
    return produceState(
        initialValue = FontPreviewLoader.buildDefaultState(path),
        key1 = path,
        key2 = packageDir?.absolutePath,
        key3 = baseDir?.absolutePath
    ) {
        value = FontPreviewLoader.loadPinnedPreview(context, path, packageDir, baseDir)
    }
}

/**
 * 候选字体列表项专用的有界 LRU（maxSize = 10）异步加载 Hook，滑出视口时自动取消挂起协程。
 */
@Composable
fun rememberCandidatePreviewTypeface(
    path: String,
    packageDir: File? = null,
    baseDir: File? = null
): State<FontPreviewLoadState> {
    val context = LocalContext.current
    return produceState(
        initialValue = FontPreviewLoader.buildDefaultState(path),
        key1 = path,
        key2 = packageDir?.absolutePath,
        key3 = baseDir?.absolutePath
    ) {
        value = FontPreviewLoader.loadCandidatePreview(context, path, packageDir, baseDir)
    }
}
