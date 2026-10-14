package io.legado.app.ui.about

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.Download
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin

/**
 * 更新日志弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Confirm` 映射为 widthFraction 0.92f / maxWidthDp 620；
 * 内容由旧 AppDialogFrame + LegadoMiuixActionButton 换为 V5 AppDialog + AppGhostButton/AppFilledButton，
 * markdown 正文仍走 Markwon + TextView（AndroidView 承载，限高可滚动，对齐旧内容容器 verticalScroll）。
 */
internal class UpdateDialog() : V5DialogFragment() {

    /** 旧 AppDialogSize.Confirm(widthFraction = 0.92f, maxWidthDp = 620) */
    override val widthFraction: Float = 0.92f
    override val maxWidthDp: Int = 620

    constructor(updateInfo: AppUpdate.UpdateInfo) : this() {
        arguments = Bundle().apply {
            putString("newVersion", updateInfo.tagName)
            putString("updateBody", updateInfo.updateLog)
            putString("url", updateInfo.downloadUrl)
            putString("name", updateInfo.fileName)
            putStringArrayList("headerNames", ArrayList(updateInfo.requestHeaders.keys))
            putStringArrayList("headerValues", ArrayList(updateInfo.requestHeaders.values))
            putStringArrayList(
                "candidateNames",
                ArrayList(updateInfo.downloadCandidates.map { it.fileName })
            )
            putStringArrayList(
                "candidateUrls",
                ArrayList(updateInfo.downloadCandidates.map { it.url })
            )
            putIntegerArrayList(
                "candidateIsDebug",
                ArrayList(updateInfo.downloadCandidates.map { if (it.isDebug) 1 else 0 })
            )
        }
    }

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val context = LocalContext.current
        val title = arguments?.getString("newVersion").orEmpty()
        val updateBody = arguments?.getString("updateBody")
        val url = arguments?.getString("url")
        val name = arguments?.getString("name")
        val candidateNames = arguments?.getStringArrayList("candidateNames").orEmpty()
        val candidateUrls = arguments?.getStringArrayList("candidateUrls").orEmpty()
        val candidateIsDebug = arguments?.getIntegerArrayList("candidateIsDebug").orEmpty()
        val downloadCandidates = candidateNames.mapIndexedNotNull { index, fileName ->
            val candidateUrl = candidateUrls.getOrNull(index) ?: return@mapIndexedNotNull null
            AppUpdate.DownloadCandidate(
                url = candidateUrl,
                fileName = fileName,
                isDebug = candidateIsDebug.getOrNull(index) == 1
            )
        }

        if (updateBody == null) {
            LaunchedEffect(Unit) {
                this@UpdateDialog.toastOnUi("没有数据")
                dismissAllowingStateLoss()
            }
            return
        }

        val markwon = remember(context) {
            Markwon.builder(context)
                .usePlugin(GlideImagesPlugin.create(context))
                .usePlugin(HtmlPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .build()
        }

        AppDialog(onDismiss = { dismissAllowingStateLoss() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(visual.density.blockGap),
            ) {
                Text(
                    text = title,
                    style = visual.type.title,
                    color = colors.onSurface,
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 360.dp),
                    factory = { ctx ->
                        TextView(ctx).apply {
                            setTextColor(colors.onSurface.toArgb())
                            typeface = ctx.uiTypeface()
                            setTextIsSelectable(true)
                            movementMethod = ScrollingMovementMethod()
                            val pad = (12 * ctx.resources.displayMetrics.density).toInt()
                            setPadding(pad, pad, pad, pad)
                        }
                    },
                    update = { textView ->
                        markwon.setMarkdown(textView, updateBody)
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(visual.density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismissAllowingStateLoss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.action_download),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when {
                                downloadCandidates.size >= 2 -> showPackageSelectDialog(downloadCandidates)
                                downloadCandidates.size == 1 -> downloadCandidate(downloadCandidates.first())
                                url != null && name != null -> downloadCandidate(
                                    AppUpdate.DownloadCandidate(url, name, isDebug = false)
                                )
                            }
                        },
                    )
                }
            }
        }

        if (packageSelectOpen) {
            AppMenuSheet(
                title = stringResource(R.string.update_select_package_title),
                actions = packageSelectCandidates.map { candidate ->
                    val label = if (candidate.isDebug) {
                        stringResource(R.string.update_package_debug)
                    } else {
                        stringResource(R.string.update_package_official)
                    }
                    "$label · ${candidate.fileName}" to {
                        packageSelectOpen = false
                        downloadCandidate(candidate)
                    }
                },
                onDismiss = { packageSelectOpen = false },
            )
        }
    }

    private fun downloadCandidate(candidate: AppUpdate.DownloadCandidate) {
        val headerNames = arguments?.getStringArrayList("headerNames").orEmpty()
        val headerValues = arguments?.getStringArrayList("headerValues").orEmpty()
        val headers = headerNames.mapIndexedNotNull { index, headerName ->
            val headerValue = headerValues.getOrNull(index)
            if (headerName.isBlank() || headerValue.isNullOrBlank()) {
                null
            } else {
                headerName to headerValue
            }
        }.toMap()
        Download.start(requireContext(), candidate.url, candidate.fileName, headers)
        toastOnUi(R.string.download_start)
    }

    internal var packageSelectOpen by mutableStateOf(false)
    internal var packageSelectCandidates by mutableStateOf<List<AppUpdate.DownloadCandidate>>(emptyList())

    private fun showPackageSelectDialog(candidates: List<AppUpdate.DownloadCandidate>) {
        packageSelectCandidates = candidates
        packageSelectOpen = true
    }

}
