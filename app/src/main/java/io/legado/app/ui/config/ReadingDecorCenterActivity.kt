package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AdvancedTipPackageManager
import io.legado.app.help.config.AdvancedTipSlot
import io.legado.app.help.config.AdvancedTitlePackageManager
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.config.EpubLoadingTemplateStore
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ShareNoteTemplateManager
import io.legado.app.ui.glass.GlassPanel
import io.legado.app.ui.glass.GlassSamplingDomain
import io.legado.app.ui.glass.glassPageBackgroundColor
import io.legado.app.ui.glass.rememberGlassConfigSignature
import io.legado.app.ui.widget.compose.AppComposeTopBar
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LocalLegadoTokens
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.rememberHasUsableBgImage
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读装饰中心：统一集中管理阅读器内的 5 类视觉素材包与模板
 * （正文气泡包、高级标题包、页眉/页脚模板包、EPUB 加载模板、分享笔记模板）。
 */
class ReadingDecorCenterActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private var summaryState by mutableStateOf(ReadingDecorSummaries())

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.visibility = View.GONE
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        val composeView = ComposeView(this).apply {
            layoutParams = binding.recyclerView.layoutParams
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    val palette = rememberAppManagementPalette()
                    ReadingDecorCenterScreen(
                        summaries = summaryState,
                        palette = palette,
                        onBack = ::supportFinishAfterTransition,
                        onOpenBubble = { startActivity<BubbleManageActivity>() },
                        onOpenAdvancedTitle = { startActivity<AdvancedTitleManageActivity>() },
                        onOpenHeaderTip = {
                            AdvancedTipManageActivity.start(
                                this@ReadingDecorCenterActivity,
                                AdvancedTipSlot.HEADER
                            )
                        },
                        onOpenFooterTip = {
                            AdvancedTipManageActivity.start(
                                this@ReadingDecorCenterActivity,
                                AdvancedTipSlot.FOOTER
                            )
                        },
                        onOpenEpubLoading = { startActivity<EpubLoadingTemplateManageActivity>() },
                        onOpenShareNote = { startActivity<ShareNoteTemplateManageActivity>() }
                    )
                }
            }
        }
        binding.root.addView(composeView, binding.root.indexOfChild(binding.recyclerView))
        refreshSummaries()
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun refreshSummaries() {
        lifecycleScope.launch {
            summaryState = withContext(Dispatchers.IO) {
                val bubbleName = BubblePackageManager.currentEntry().config.name
                val advTitleName = AdvancedTitlePackageManager.loadEntries()
                    .firstOrNull { it.config.id == AdvancedTitlePackageManager.activeId() }
                    ?.config?.name
                    .orEmpty()
                val headerManager = AdvancedTipPackageManager.of(AdvancedTipSlot.HEADER)
                val headerName = headerManager.loadEntries()
                    .firstOrNull { it.id == headerManager.activeId() }
                    ?.name
                    .orEmpty()
                val footerManager = AdvancedTipPackageManager.of(AdvancedTipSlot.FOOTER)
                val footerName = footerManager.loadEntries()
                    .firstOrNull { it.id == footerManager.activeId() }
                    ?.name
                    .orEmpty()
                val epubName = EpubLoadingTemplateStore.selected(this@ReadingDecorCenterActivity).name
                val shareName = ShareNoteTemplateManager.loadEntries()
                    .firstOrNull { it.dirName == ShareNoteTemplateManager.activeDirName() }
                    ?.meta?.name
                    .orEmpty()
                ReadingDecorSummaries(
                    bubble = bubbleName,
                    advancedTitle = advTitleName,
                    headerTip = headerName,
                    footerTip = footerName,
                    epubLoading = epubName,
                    shareNote = shareName
                )
            }
        }
    }
}

private data class ReadingDecorSummaries(
    val bubble: String = "",
    val advancedTitle: String = "",
    val headerTip: String = "",
    val footerTip: String = "",
    val epubLoading: String = "",
    val shareNote: String = ""
)

@Composable
private fun ReadingDecorCenterScreen(
    summaries: ReadingDecorSummaries,
    palette: AppManagementPalette,
    onBack: () -> Unit,
    onOpenBubble: () -> Unit,
    onOpenAdvancedTitle: () -> Unit,
    onOpenHeaderTip: () -> Unit,
    onOpenFooterTip: () -> Unit,
    onOpenEpubLoading: () -> Unit,
    onOpenShareNote: () -> Unit
) {
    val wallpaperVisible = rememberHasUsableBgImage() && !AppConfig.isEInkMode
    val glassSignature = rememberGlassConfigSignature()
    val effectiveGlassSignature = glassSignature.takeIf { wallpaperVisible }
    val panelRadiusDp = palette.settings.panelRadiusPx.takeIf { it > 0f }?.let {
        with(androidx.compose.ui.platform.LocalDensity.current) { it.toDp() }
    } ?: (LocalLegadoTokens.current?.corners?.panelRadiusDp ?: 14f).dp
    val panelShape = RoundedCornerShape(panelRadiusDp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(glassPageBackgroundColor(palette.settings.page, wallpaperVisible))
    ) {
        AppComposeTopBar(
            title = stringResource(R.string.reading_decor_center_title),
            palette = palette,
            onBack = onBack,
            wallpaperVisible = wallpaperVisible,
            glassSignature = glassSignature
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("summary_hint") {
                Text(
                    text = stringResource(R.string.reading_decor_center_summary),
                    color = palette.settings.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = palette.settings.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 2.dp)
                )
            }
            item("decor_items") {
                val cardContent: @Composable () -> Unit = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        DecorItemRow(
                            title = stringResource(R.string.bubble_manage),
                            summary = summaries.bubble.ifBlank { stringResource(R.string.bubble_manage_summary) },
                            palette = palette,
                            isFirst = true,
                            onClick = onOpenBubble
                        )
                        DecorItemRow(
                            title = stringResource(R.string.reading_decor_advanced_title),
                            summary = summaries.advancedTitle.ifBlank { "-" },
                            palette = palette,
                            onClick = onOpenAdvancedTitle
                        )
                        DecorItemRow(
                            title = stringResource(R.string.reading_decor_header_tip),
                            summary = summaries.headerTip.ifBlank { "-" },
                            palette = palette,
                            onClick = onOpenHeaderTip
                        )
                        DecorItemRow(
                            title = stringResource(R.string.reading_decor_footer_tip),
                            summary = summaries.footerTip.ifBlank { "-" },
                            palette = palette,
                            onClick = onOpenFooterTip
                        )
                        DecorItemRow(
                            title = stringResource(R.string.epub_loading_templates),
                            summary = summaries.epubLoading.ifBlank { "-" },
                            palette = palette,
                            onClick = onOpenEpubLoading
                        )
                        DecorItemRow(
                            title = stringResource(R.string.share_note_template_manage),
                            summary = summaries.shareNote.ifBlank { stringResource(R.string.share_note_template_manage_summary) },
                            palette = palette,
                            isLast = true,
                            showDivider = false,
                            onClick = onOpenShareNote
                        )
                    }
                }
                if (effectiveGlassSignature != null) {
                    GlassPanel(
                        domain = GlassSamplingDomain.WALLPAPER,
                        cornerRadiusPx = palette.settings.panelRadiusPx,
                        configSignature = effectiveGlassSignature,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(panelShape)
                    ) {
                        cardContent()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 与玻璃分支保持一致地裁剪：appSettingPanelBackground 只画圆角路径、
                            // 不限制子项绘制，行按压底色是直角矩形，不裁剪会把首/末行的圆角盖成方角。
                            .clip(panelShape)
                            .appSettingPanelBackground(
                                normalColor = palette.settings.row,
                                borderColor = palette.settings.border,
                                panelImage = null,
                                radiusPx = palette.settings.panelRadiusPx
                            )
                    ) {
                        cardContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun DecorItemRow(
    title: String,
    summary: String,
    palette: AppManagementPalette,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = palette.settings.rowPressed,
                dividerColor = palette.settings.divider,
                showDivider = showDivider,
                radiusPx = palette.settings.panelRadiusPx,
                isFirst = isFirst,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.settings.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = summary,
                color = palette.settings.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = palette.settings.secondaryText
        )
    }
}
