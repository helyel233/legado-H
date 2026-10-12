package io.legado.app.ui.host

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import io.legado.app.ui.file.HandleFileContract

/**
 * P0 框架件：文件选择/导出门面（2026-10-12 组件审计）。
 *
 * 此前每个页面直接 import 旧 [HandleFileContract] + 手写 launcher 三件套，
 * 新旧弹层体系在同一页面混用。管理页迁移时统一走这里：
 *
 * ```
 * val pickers = rememberAppFilePickers(
 *     onPicked = { uri -> importFrom(uri) },
 * )
 * // 选文件
 * pickers.pick(allowExtensions = arrayOf("txt", "json"))
 * // 导出（data 可为 File 或字符串内容）
 * pickers.export(suggestedName = "sources.json", mime = "application/json", data = file)
 * ```
 */
class AppFilePickers internal constructor(
    private val launch: (HandleFileContract.HandleFileParam.() -> Unit) -> Unit,
) {

    /** 选择文件（可限制扩展名）。 */
    fun pick(
        title: String? = null,
        allowExtensions: Array<String> = arrayOf(),
        requestCode: Int = 0,
    ) {
        launch {
            mode = HandleFileContract.FILE
            this.title = title
            this.allowExtensions = allowExtensions
            this.requestCode = requestCode
        }
    }

    /** 选择目录。 */
    fun pickDir(title: String? = null) {
        launch {
            mode = HandleFileContract.DIR
            this.title = title
        }
    }

    /** 选图片（jpg/png/bmp/webp）。 */
    fun pickImage(title: String? = null) {
        launch {
            mode = HandleFileContract.IMAGE
            this.title = title
        }
    }

    /** 导出：SAF 目标位置 + 写入内容（data 可为 File / String / ByteArray）。 */
    fun export(
        suggestedName: String,
        mime: String,
        data: Any,
        requestCode: Int = 0,
    ) {
        launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(suggestedName, data, mime)
            this.requestCode = requestCode
        }
    }
}

@Composable
fun rememberAppFilePickers(
    onPicked: (uri: android.net.Uri, requestCode: Int) -> Unit,
): AppFilePickers {
    val launcher = rememberLauncherForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { onPicked(it, result.requestCode) }
    }
    return AppFilePickers { config -> launcher.launch(config) }
}

/** 只导出的简化形态（不需要选文件入口的页面）。 */
@Composable
fun rememberAppFileExporter(
    onReady: (uri: android.net.Uri) -> Unit,
): AppFilePickers = rememberAppFilePickers { uri, _ -> onReady(uri) }
