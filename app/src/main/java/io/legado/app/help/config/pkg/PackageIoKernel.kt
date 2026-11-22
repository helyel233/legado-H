package io.legado.app.help.config.pkg

import io.legado.app.help.config.AtomicByteFileStore
import io.legado.app.help.config.AtomicTextFileStore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.compress.SafeZipExtractor
import io.legado.app.utils.compress.SafeZipLimits
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.compress.readTextLimited
import io.legado.app.utils.isSameOrSubFileOf
import io.legado.app.utils.normalizeFileName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

enum class PackageKind(val id: String) {
    ART("art"),
    PARAM("param"),
    KIT("kit"),
    ICON_SLOT("icon_slot"),
    COVER_SLOT("cover_slot");

    companion object {
        fun fromId(raw: String?): PackageKind? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == normalized }
        }
    }
}

/**
 * Strategy contract for package-kind-specific remote scopes, directory validation, and ZIP limits.
 */
interface PackageKindPolicy<TEntry> {
    val kind: PackageKind
    val manifestFileName: String
    val remoteScope: String
    val zipLimits: SafeZipLimits
    fun validateDirectory(dir: File): Boolean
    fun entryDisplayName(entry: TEntry): String
}

/**
 * Unified storage & ZIP IO kernel shared by package repositories.
 *
 * Responsibilities are strictly bounded to:
 * 1. Atomic file writes/reads with `.staging` + `.backup` crash recovery;
 * 2. Safe ZIP extraction (`SafeZipExtractor`) and atomic ZIP packaging;
 * 3. Remote list cache persistence and safe package directory allocation/deletion.
 */
object PackageIoKernel {

    val defaultZipLimits = SafeZipLimits(
        maxEntries = 1_024,
        maxEntryBytes = 64L * 1024L * 1024L,
        maxTotalBytes = 256L * 1024L * 1024L,
        maxCompressionRatio = 300L
    )

    fun readTextWithRecovery(target: File, maxBytes: Long): String? {
        val store = AtomicTextFileStore(target)
        store.recoverInterruptedCommit()
        if (!target.isFile) return null
        return runCatching { target.readTextLimited(maxBytes) }.getOrNull()
    }

    fun writeTextAtomic(
        target: File,
        content: String,
        verify: (String) -> Boolean = { it == content }
    ) {
        AtomicTextFileStore(target).writeVerified(content, verify)
    }

    fun deleteAtomicText(target: File): Long {
        return AtomicTextFileStore(target).delete()
    }

    fun writeBytesAtomic(
        target: File,
        bytes: ByteArray,
        verify: (File) -> Boolean = { it.isFile && it.length() == bytes.size.toLong() }
    ) {
        AtomicByteFileStore(target).writeVerified(bytes, verify)
    }

    fun extractZipSafe(
        zipFile: File,
        destination: File,
        limits: SafeZipLimits = defaultZipLimits,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return SafeZipExtractor.extract(zipFile, destination, limits, filter)
    }

    fun zipDirectoryAtomic(sourceDir: File, targetZip: File) {
        require(sourceDir.isDirectory) { "Source directory does not exist: ${sourceDir.absolutePath}" }
        val files = sourceDir.listFiles()?.toList().orEmpty()
        zipFilesAtomic(files, targetZip)
    }

    fun zipFilesAtomic(files: List<File>, targetZip: File) {
        targetZip.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "Failed to create ${parent.absolutePath}" }
        }
        val staging = File(targetZip.parentFile, ".${targetZip.name}.staging.zip")
        if (staging.exists()) staging.delete()
        try {
            val ok = ZipUtils.zipFiles(files, staging)
            check(ok && staging.isFile && staging.length() > 0L) {
                "Failed to build ZIP: ${targetZip.name}"
            }
            ZipFile(staging).use { zip ->
                check(zip.size() >= 0) { "Invalid ZIP archive: ${targetZip.name}" }
            }
            if (targetZip.exists()) {
                Files.delete(targetZip.toPath())
            }
            Files.move(
                staging.toPath(),
                targetZip.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun <T> readRemoteCache(
        cacheFile: File,
        maxBytes: Long = 1024L * 1024L,
        parser: (String) -> List<T>
    ): List<T> {
        val text = readTextWithRecovery(cacheFile, maxBytes) ?: return emptyList()
        return runCatching { parser(text) }.getOrDefault(emptyList())
    }

    fun writeRemoteCache(cacheFile: File, json: String) {
        val existing = readTextWithRecovery(cacheFile, 2L * 1024L * 1024L)
        if (existing == json) return
        writeTextAtomic(cacheFile, json)
    }

    fun uniqueDirName(
        rootDir: File,
        preferredName: String,
        fallbackSlug: String = "package",
        matchesExisting: (File) -> Boolean = { false }
    ): String {
        val base = preferredName.normalizeFileName().ifBlank { fallbackSlug }
        val firstDir = File(rootDir, base)
        if (!firstDir.exists() || matchesExisting(firstDir)) {
            return base
        }
        var suffix = 1
        while (true) {
            val candidate = "${base}_$suffix"
            val dir = File(rootDir, candidate)
            if (!dir.exists() || matchesExisting(dir)) {
                return candidate
            }
            suffix++
        }
    }

    fun deletePackageDirSafely(dir: File, rootDir: File): Boolean {
        val canonicalRoot = runCatching { rootDir.canonicalFile }.getOrNull() ?: return false
        val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        if (canonicalDir == canonicalRoot || !canonicalDir.isSameOrSubFileOf(canonicalRoot)) {
            return false
        }
        if (!canonicalDir.exists()) return true
        FileUtils.delete(canonicalDir, deleteRootDir = true)
        return !canonicalDir.exists()
    }
}
