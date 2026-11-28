package io.legado.app.ui.book.read

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
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
import androidx.fragment.app.activityViewModels
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LocalMiuixContainerSurfaceColor
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.resolveL3ControlSurfaceColor
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import splitties.init.appCtx

/**
 * 起效的替换规则
 */
class EffectiveReplacesDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private val viewModel by activityViewModels<ReadBookViewModel>()
    private val chineseConvert by lazy { ReplaceRule(0, appCtx.getString(R.string.i18n_final_cn_convert)) }
    private val items = mutableStateListOf<ReplaceRule>()

    private var isEdit = false

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == AppCompatActivity.RESULT_OK) {
                isEdit = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items.clear()
        val effectiveReplaceRules = ReadBook.curTextChapter?.effectiveReplaceRules ?: emptyList()
        items.addAll(effectiveReplaceRules)
        if (AppConfig.chineseConverterType > 0) {
            items.add(chineseConvert)
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
                val palette = style.toMiuixPalette()
                AppDialogFrame(
                    title = stringResource(R.string.effective_replaces),
                    scrollContent = false,
                    content = {
                        val rowSurfaceColor = resolveL3ControlSurfaceColor(
                            baseColor = palette.surfaceVariant,
                            containerColor = LocalMiuixContainerSurfaceColor.current,
                            palette = palette,
                            isNight = AppConfig.isNightTheme,
                            isEInk = AppConfig.isEInkMode
                        )
                        val closeButtonSurfaceColor = resolveL3ControlSurfaceColor(
                            baseColor = rowSurfaceColor,
                            containerColor = rowSurfaceColor,
                            palette = palette,
                            isNight = AppConfig.isNightTheme,
                            isEInk = AppConfig.isEInkMode
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(items) { item ->
                                LegadoMiuixCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(style.actionRadius))
                                        .clickable {
                                            if (item == chineseConvert) {
                                                showChineseConvertAlert()
                                            } else {
                                                editActivity.launch(
                                                    ReplaceEditActivity.startIntent(requireContext(), item.id)
                                                )
                                            }
                                        },
                                    color = rowSurfaceColor,
                                    contentColor = palette.primaryText,
                                    cornerRadius = style.actionRadius,
                                    insidePadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = item.name,
                                            color = palette.primaryText,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(closeButtonSurfaceColor)
                                                .clickable {
                                                    isEdit = true
                                                    items.remove(item)
                                                    if (item == chineseConvert) {
                                                        AppConfig.chineseConverterType = 0
                                                    } else {
                                                        item.isEnabled = false
                                                        appDb.replaceRuleDao.insert(item)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_baseline_close),
                                                contentDescription = stringResource(R.string.close),
                                                tint = palette.secondaryText,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.close),
                            palette = palette,
                            onClick = { dismissAllowingStateLoss() },
                            cornerRadius = style.actionRadius
                        )
                    }
                )
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (isEdit) {
            viewModel.replaceRuleChanged()
        }
    }

    private fun showChineseConvertAlert() {
        showComposeChoiceListDialog(
            title = getString(R.string.chinese_converter),
            labels = resources.getStringArray(R.array.chinese_mode).toList(),
            selectedIndex = AppConfig.chineseConverterType
        ) { i ->
            if (AppConfig.chineseConverterType != i) {
                AppConfig.chineseConverterType = i
                isEdit = true
                if (i == 0) {
                    items.remove(chineseConvert)
                }
            }
        }
    }
}
