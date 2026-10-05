package io.legado.app.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.update.AppUpdate
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.Download
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.utils.toastOnUi
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin

class UpdateDialog() : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Confirm

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val style = rememberAppDialogStyle()
                val title = arguments?.getString("newVersion").orEmpty()
                val updateBody = arguments?.getString("updateBody")
                val url = arguments?.getString("url")
                val name = arguments?.getString("name")
                val headerNames = arguments?.getStringArrayList("headerNames").orEmpty()
                val headerValues = arguments?.getStringArrayList("headerValues").orEmpty()
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
                    toastOnUi("没有数据")
                    dismissAllowingStateLoss()
                    return@setContent
                }

                val context = LocalContext.current
                val markwon = Markwon.builder(context)
                    .usePlugin(GlideImagesPlugin.create(context))
                    .usePlugin(HtmlPlugin.create())
                    .usePlugin(TablePlugin.create(context))
                    .build()

                AppDialogFrame(
                    title = title,
                    content = {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth(),
                            factory = { ctx ->
                                TextView(ctx).apply {
                                    setTextColor(style.primaryText.toArgb())
                                    typeface = ctx.uiTypeface()
                                    setTextIsSelectable(true)
                                    val pad = (12 * ctx.resources.displayMetrics.density).toInt()
                                    setPadding(pad, pad, pad, pad)
                                }
                            },
                            update = { textView ->
                                markwon.setMarkdown(textView, updateBody)
                            }
                        )
                    },
                    actions = {
                        val palette = style.toMiuixPalette()
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.cancel),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.action_download),
                            palette = palette,
                            onClick = {
                                when {
                                    downloadCandidates.size >= 2 -> showPackageSelectDialog(
                                        downloadCandidates
                                    )
                                    downloadCandidates.size == 1 -> downloadCandidate(
                                        downloadCandidates.first()
                                    )
                                    url != null && name != null -> downloadCandidate(
                                        AppUpdate.DownloadCandidate(url, name, isDebug = false)
                                    )
                                }
                            },
                            primary = true,
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
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

    private fun showPackageSelectDialog(candidates: List<AppUpdate.DownloadCandidate>) {
        showComposeActionListDialog(
            title = getString(R.string.update_select_package_title),
            labels = candidates.map {
                if (it.isDebug) {
                    getString(R.string.update_package_debug)
                } else {
                    getString(R.string.update_package_official)
                }
            },
            descriptions = candidates.map { it.fileName },
            onSelected = { index ->
                candidates.getOrNull(index)?.let(::downloadCandidate)
            }
        )
    }

}
