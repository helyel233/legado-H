package io.legado.app.utils.compress

import android.annotation.SuppressLint
import io.legado.app.utils.DebugLog
import io.legado.app.utils.isSameOrSubFileOf
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@SuppressLint("ObsoleteSdkInt")
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ZipUtils {

    fun gzipByteArray(byteArray: ByteArray): ByteArray {
        val byteOut = ByteArrayOutputStream()
        val zip = GZIPOutputStream(byteOut)
        return zip.use {
            it.write(byteArray)
            byteOut.use {
                byteOut.toByteArray()
            }
        }
    }

    fun zipByteArray(byteArray: ByteArray, fileName: String): ByteArray {
        val byteOut = ByteArrayOutputStream()
        val zipOutputStream = ZipOutputStream(byteOut)
        zipOutputStream.putNextEntry(ZipEntry(fileName))
        zipOutputStream.write(byteArray)
        zipOutputStream.closeEntry()
        zipOutputStream.finish()
        return zipOutputStream.use {
            byteOut.use {
                byteOut.toByteArray()
            }
        }
    }

    /**
     * 压缩源。
     *
     * @param file      源文件或源目录
     * @param entryRoot 在 ZIP 内的根路径；为空时使用源自身的名字（用于源目录名与 ZIP 内目标目录名不一致的场景）
     */
    data class ZipSource(val file: File, val entryRoot: String = "")

    /**
     * Zip the files.
     *
     * @param srcFiles    The source of files.
     * @param zipFilePath The path of ZIP file.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    suspend fun zipFiles(
        srcFiles: Collection<String>,
        zipFilePath: String
    ): Boolean {
        return zipFiles(srcFiles, zipFilePath, null)
    }

    /**
     * Zip the files.
     *
     * @param srcFilePaths The paths of source files.
     * @param zipFilePath  The path of ZIP file.
     * @param comment      The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    suspend fun zipFiles(
        srcFilePaths: Collection<String>?,
        zipFilePath: String?,
        comment: String?
    ): Boolean = withContext(IO) {
        if (srcFilePaths == null || zipFilePath == null) return@withContext false
        val sources = srcFilePaths.mapNotNull { getFileByPath(it) }.map { ZipSource(it) }
        zipSources(sources, File(zipFilePath), comment, null)
    }

    /**
     * Zip the files with progress.
     *
     * @param sources     The sources of files.
     * @param zipFilePath The path of ZIP file.
     * @param onProgress  进度回调（已处理字节数, 总字节数），在 IO 线程回调。
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    suspend fun zipFiles(
        sources: Collection<ZipSource>,
        zipFilePath: String,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)?
    ): Boolean = withContext(IO) {
        zipSources(sources, File(zipFilePath), null, onProgress)
    }

    /**
     * Zip the files.
     *
     * @param srcFiles The source of files.
     * @param zipFile  The ZIP file.
     * @param comment  The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun zipFiles(
        srcFiles: Collection<File>?,
        zipFile: File?,
        comment: String? = null
    ): Boolean {
        if (srcFiles == null || zipFile == null) return false
        return zipSources(srcFiles.map { ZipSource(it) }, zipFile, comment, null)
    }

    /**
     * Zip the file.
     *
     * @param srcFilePath The path of source file.
     * @param zipFilePath The path of ZIP file.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun zipFile(
        srcFilePath: String,
        zipFilePath: String
    ): Boolean {
        return zipFile(getFileByPath(srcFilePath), getFileByPath(zipFilePath), null)
    }

    /**
     * Zip the file.
     *
     * @param srcFilePath The path of source file.
     * @param zipFilePath The path of ZIP file.
     * @param comment     The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun zipFile(
        srcFilePath: String,
        zipFilePath: String,
        comment: String
    ): Boolean {
        return zipFile(getFileByPath(srcFilePath), getFileByPath(zipFilePath), comment)
    }

    /**
     * Zip the file.
     *
     * @param srcFile The source of file.
     * @param zipFile The ZIP file.
     * @param comment The comment.
     * @return `true`: success<br></br>`false`: fail
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun zipFile(
        srcFile: File?,
        zipFile: File?,
        comment: String? = null
    ): Boolean {
        if (srcFile == null || zipFile == null) return false
        return zipSources(listOf(ZipSource(srcFile)), zipFile, comment, null)
    }

    private fun zipSources(
        sources: Collection<ZipSource>,
        zipFile: File,
        comment: String?,
        onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)?
    ): Boolean {
        val totalBytes = sources.sumOf { fileSizeOf(it.file) }
        val progress = ZipProgress(totalBytes, onProgress)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile), BUFFER_SIZE)).use { zos ->
            // 文本类条目（JSON/章节 txt）压得更多以减小上传体积；
            // 已压缩格式走 STORED 跳过 deflate，省出的 CPU 补贴这里
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            for (source in sources) {
                val entryPath = when {
                    source.entryRoot.isBlank() -> source.file.name
                    source.file.isFile -> "${source.entryRoot}/${source.file.name}"
                    else -> source.entryRoot
                }
                if (!zipFile(source.file, entryPath, zos, comment, progress)) return false
            }
            progress.reportCompleted()
        }
        return true
    }

    @Throws(IOException::class)
    private fun zipFile(
        srcFile: File,
        entryPath: String,
        zos: ZipOutputStream,
        comment: String?,
        progress: ZipProgress
    ): Boolean {
        if (!srcFile.exists()) return true
        if (srcFile.isDirectory) {
            val fileList = srcFile.listFiles()
            if (fileList == null || fileList.isEmpty()) {
                val entry = ZipEntry("$entryPath/").also { e ->
                    e.comment = comment
                    e.time = srcFile.lastModified()
                }
                zos.putNextEntry(entry)
                zos.closeEntry()
            } else {
                for (file in fileList) {
                    if (!zipFile(file, "$entryPath/${file.name}", zos, comment, progress)) return false
                }
            }
        } else {
            BufferedInputStream(FileInputStream(srcFile)).use {
                val entry = storedEntry(srcFile, entryPath, comment)
                    // 预读后文件若被并发修改，STORED 的 size/CRC 将失配导致整包失败，回退 DEFLATED
                    ?.takeIf { srcFile.lastModified() == it.lastModified }
                    ?.entry
                    ?: ZipEntry(entryPath).also { e ->
                        e.comment = comment
                        // 条目时间取源文件 mtime 而非打包时刻：相同输入产出字节相同的 zip，
                        // 资源包内容哈希比对（跳过重复上传）依赖此确定性
                        e.time = srcFile.lastModified()
                    }
                zos.putNextEntry(entry)
                it.copyTo(zos, BUFFER_SIZE)
                zos.closeEntry()
            }
            progress.advance(srcFile.length())
        }
        return true
    }

    /**
     * 已压缩格式（图片/epub/音视频等）deflate 几乎无收益，预计算 CRC/size 后
     * 以 STORED 直存，避免白耗 CPU；返回 null 表示按默认 DEFLATED 压缩。
     * lastModified 为预读完成时的文件时间，供写入前复核是否被并发修改。
     */
    @Throws(IOException::class)
    private fun storedEntry(srcFile: File, entryPath: String, comment: String?): StoredCandidate? {
        if (srcFile.extension.lowercase(Locale.ROOT) !in STORED_EXTENSIONS) return null
        val crc32 = CRC32()
        var size = 0L
        BufferedInputStream(FileInputStream(srcFile)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc32.update(buffer, 0, read)
                size += read
            }
        }
        val entry = ZipEntry(entryPath).also { entry ->
            entry.comment = comment
            entry.method = ZipEntry.STORED
            entry.size = size
            entry.compressedSize = size
            entry.crc = crc32.value
            entry.time = srcFile.lastModified()
        }
        return StoredCandidate(entry, srcFile.lastModified())
    }

    private class StoredCandidate(val entry: ZipEntry, val lastModified: Long)

    private fun fileSizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += fileSizeOf(it) }
        return total
    }

    private class ZipProgress(
        private val totalBytes: Long,
        private val onProgress: ((processedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        private var processedBytes = 0L
        private var lastReportTime = 0L

        fun advance(bytes: Long) {
            // 源文件可能在预统计后增长，兜底避免 processed 超过 total 导致进度回溢
            processedBytes = (processedBytes + bytes).coerceAtMost(totalBytes)
            val callback = onProgress ?: return
            val now = System.currentTimeMillis()
            if (now - lastReportTime < PROGRESS_INTERVAL_MS) return
            lastReportTime = now
            callback(processedBytes, totalBytes)
        }

        fun reportCompleted() {
            onProgress?.invoke(processedBytes, totalBytes)
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 100L

    /** deflate 无收益、以 STORED 直存的已压缩格式扩展名 */
    private val STORED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif",
        "epub", "zip", "apk", "jar", "7z", "gz", "tgz", "bz2", "xz", "zst",
        "mp3", "m4a", "aac", "ogg", "opus", "flac",
        "mp4", "mkv", "webm", "woff", "woff2", "pdf"
    )

    @Throws(SecurityException::class)
    fun unZipToPath(file: File, path: String, filter: ((String) -> Boolean)? = null): List<File> {
        return FileInputStream(file).use {
            unZipToPath(it, path, filter)
        }
    }

    @Throws(SecurityException::class)
    fun unZipToPath(file: File, dir: File, filter: ((String) -> Boolean)? = null): List<File> {
        return FileInputStream(file).use {
            unZipToPath(it, dir, filter)
        }
    }

    @Throws(SecurityException::class)
    fun unZipToPath(
        inputStream: InputStream,
        path: String,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return ZipInputStream(inputStream).use {
            unZipToPath(it, File(path), filter)
        }
    }

    @Throws(SecurityException::class)
    fun unZipToPath(
        inputStream: InputStream,
        dir: File,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return ZipInputStream(inputStream).use {
            unZipToPath(it, dir, filter)
        }
    }

    @Throws(SecurityException::class)
    private fun unZipToPath(
        zipInputStream: ZipInputStream,
        dir: File,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        val files = arrayListOf<File>()
        var entry: ZipEntry?
        while (zipInputStream.nextEntry.also { entry = it } != null) {
            val entryName = entry!!.name
            val entryFile = File(dir, entryName)
            if (!entryFile.isSameOrSubFileOf(dir)) {
                throw SecurityException("压缩文件只能解压到指定路径")
            }
            if (entry.isDirectory) {
                if (!entryFile.exists()) {
                    entryFile.mkdirs()
                }
                continue
            }
            if (entryFile.parentFile?.exists() != true) {
                entryFile.parentFile?.mkdirs()
            }
            if (filter != null && !filter.invoke(entryName)) continue
            if (!entryFile.exists()) {
                entryFile.createNewFile()
                entryFile.setReadable(true)
                entryFile.setExecutable(true)
            }
            FileOutputStream(entryFile).use {
                zipInputStream.copyTo(it)
                files.add(entryFile)
            }
        }
        return files
    }

    /* 遍历目录获取所有文件名 */
    @Throws(SecurityException::class)
    fun getFilesName(
        inputStream: InputStream,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        return ZipInputStream(inputStream).use {
            getFilesName(it, filter)
        }
    }

    @Throws(SecurityException::class)
    private fun getFilesName(
        zipInputStream: ZipInputStream,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        val fileNames = mutableListOf<String>()
        var entry: ZipEntry?
        while (zipInputStream.nextEntry.also { entry = it } != null) {
            if (entry!!.isDirectory) {
                continue
            }
            val fileName = entry.name
            if (filter != null && filter.invoke(fileName))
                fileNames.add(fileName)
        }
        return fileNames
    }

    /**
     * Return the files' path in ZIP file.
     *
     * @param zipFilePath The path of ZIP file.
     * @return the files' path in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getFilesPath(zipFilePath: String): List<String>? {
        return getFilesPath(getFileByPath(zipFilePath))
    }

    /**
     * Return the files' path in ZIP file.
     *
     * @param zipFile The ZIP file.
     * @return the files' path in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getFilesPath(zipFile: File?): List<String>? {
        if (zipFile == null) return null
        val paths = ArrayList<String>()
        val zip = ZipFile(zipFile)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entryName = (entries.nextElement() as ZipEntry).name
            if (entryName.contains("../")) {
                DebugLog.e(javaClass.name, "entryName: $entryName is dangerous!")
                paths.add(entryName)
            } else {
                paths.add(entryName)
            }
        }
        zip.close()
        return paths
    }

    /**
     * Return the files' comment in ZIP file.
     *
     * @param zipFilePath The path of ZIP file.
     * @return the files' comment in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getComments(zipFilePath: String): List<String>? {
        return getComments(getFileByPath(zipFilePath))
    }

    /**
     * Return the files' comment in ZIP file.
     *
     * @param zipFile The ZIP file.
     * @return the files' comment in ZIP file
     * @throws IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    fun getComments(zipFile: File?): List<String>? {
        if (zipFile == null) return null
        val comments = ArrayList<String>()
        val zip = ZipFile(zipFile)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement() as ZipEntry
            comments.add(entry.comment)
        }
        zip.close()
        return comments
    }

    private fun getFileByPath(filePath: String): File? {
        return if (isSpace(filePath)) null else File(filePath)
    }

    private fun isSpace(s: String?): Boolean {
        if (s == null) return true
        var i = 0
        val len = s.length
        while (i < len) {
            if (!Character.isWhitespace(s[i])) {
                return false
            }
            ++i
        }
        return true
    }
}
