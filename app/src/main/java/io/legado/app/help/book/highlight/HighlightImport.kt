package io.legado.app.help.book.highlight

import android.net.Uri
import io.legado.app.utils.FileDoc
import io.legado.app.utils.openInputStream
import io.legado.app.R
import splitties.init.appCtx

object HighlightImport {
    fun isHighlightFile(file: FileDoc): Boolean = file.name.endsWith(".red", true) && runCatching {
        file.openInputStream().getOrThrow().use {
            HighlightPackageParser.resourceType(HighlightPackageParser.readLimited(it,
                HighlightPackageParser.MAX_PACKAGE_BYTES)) == "highlightRule"
        }
    }.getOrDefault(false)

    data class Report(
        var added: Int = 0,
        var duplicates: Int = 0,
        var skippedFiles: Int = 0,
        val messages: MutableList<String> = arrayListOf()
    ) {
        fun summary(): String = buildString {
            append(appCtx.getString(R.string.i18n_final_import_summary, added, duplicates))
            if (skippedFiles > 0) append(appCtx.getString(R.string.i18n_final_import_skipped_files, skippedFiles))
            if (messages.isNotEmpty()) append("\n\n").append(messages.distinct().joinToString("\n"))
        }
    }

    fun importUri(uri: Uri, bookUrl: String?): Report {
        require(uri.scheme == "content" || uri.scheme == "file") { "请选择本地 .red 高亮规则文件" }
        val file = FileDoc.fromUri(uri, false)
        require(file.name.endsWith(".red", true)) { "请选择 .red 高亮规则文件" }
        val report = Report()
        file.openInputStream().getOrThrow().use { input ->
            runCatching {
                val bytes = HighlightPackageParser.readLimited(input, HighlightPackageParser.MAX_PACKAGE_BYTES)
                val pack = HighlightPackageParser.parse(bytes, bookUrl)
                HighlightRules.store.importPackage(pack)
            }.onSuccess { result ->
                report.added += result.added
                report.duplicates += result.duplicates
                if (result.warnings.isNotEmpty()) {
                    // Keep the full per-rule notice on each management row.
                    report.messages.add(file.name + "：" +
                        result.warnings.size + appCtx.getString(R.string.misc_rules_have_compatibility_notes_see))
                }
            }.onFailure {
                report.skippedFiles++
                report.messages.add(file.name + "：" + (it.message ?: appCtx.getString(R.string.misc_cannot_read)))
            }
        }
        return report
    }
}
