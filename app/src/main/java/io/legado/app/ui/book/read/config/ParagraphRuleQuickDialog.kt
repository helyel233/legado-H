package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.host.HostSourceLoginActivity
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.uikit.v5.token.ShapeKind
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 段落规则快速面板（V5 基座版）。
 *
 * 旧基类 `ReaderBottomSheetComposeDialogFragment`(BOTTOM + AnimDialogBottom + MATCH_PARENT) 对应到 V5 基座的
 * `dialogTheme/dialogWidth/dialogHeight/dialogGravity/dialogWindowAnimations` 五个 open val；
 * 旧 `maxSheetHeightFraction = 0.66f` 改为面板内 `heightIn(max = screenHeightDp * 0.66f)`。
 * **外观跟随阅读页背景色**：改用共享的 `rememberReaderPagePalette()`（与旧 `rememberReaderMenuDialogStyle()` 同源）。
 * 组件替换：`ReaderSheetHeader/ReaderSectionCard/ReaderTextAction` 内联为 V5 文本+Box 色块，
 * `LegadoMiuixSwitch` → M3 Switch（套面板 palette 与 V5 语义色）。
 * 「开/关即写 `BookParagraphRule` 并刷新阅读排版」「登录态跳转 HostSourceLoginActivity」「跳转管理页」等语义不变。
 */
internal class ParagraphRuleQuickDialog : V5DialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val maxSheetHeightFraction: Float = 0.66f

    private val bookUrl: String
        get() = requireArguments().getString(ARG_BOOK_URL).orEmpty()

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val v5Colors = LocalSemanticColors.current
        val palette = rememberReaderPagePalette()
        val surface = Color(palette.surface)
        val panel = Color(palette.panel)
        val textColor = Color(palette.textColor)
        val accent = Color(palette.accentColor)
        val maxHeight = (LocalConfiguration.current.screenHeightDp * maxSheetHeightFraction)
            .toInt()
            .dp

        var rules by remember { mutableStateOf<List<ParagraphRule>>(emptyList()) }
        var enabledIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
        LaunchedEffect(bookUrl) {
            val (loadedRules, loadedIds) = withContext(Dispatchers.IO) {
                appDb.paragraphRuleDao.all() to
                        appDb.paragraphRuleDao.enabledRuleIdsForBook(bookUrl).toSet()
            }
            rules = loadedRules
            enabledIds = loadedIds
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = surface,
            contentColor = textColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.paragraph_rule),
                        style = visual.type.title,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.paragraph_rule_manage),
                        style = visual.type.caption,
                        color = accent,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(visual.shape(ShapeKind.BUTTON))
                            .clickable(onClick = ::openManage)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(visual.shape(ShapeKind.BAR))
                        .background(panel)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        itemsIndexed(
                            items = rules,
                            key = { _, rule -> rule.id }
                        ) { index, rule ->
                            val checked = rule.id in enabledIds
                            ParagraphRuleRow(
                                rule = rule,
                                checked = checked,
                                panelColor = panel,
                                textColor = textColor,
                                accent = accent,
                                switchCheckedTrack = accent,
                                switchUncheckedThumb = v5Colors.outline,
                                onCheckedChange = { next ->
                                    enabledIds = if (next) {
                                        enabledIds + rule.id
                                    } else {
                                        enabledIds - rule.id
                                    }
                                    updateRuleEnabled(rule, next, index)
                                },
                                onLogin = { openLogin(rule) }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ParagraphRuleRow(
        rule: ParagraphRule,
        checked: Boolean,
        panelColor: Color,
        textColor: Color,
        accent: Color,
        switchCheckedTrack: Color,
        switchUncheckedThumb: Color,
        onCheckedChange: (Boolean) -> Unit,
        onLogin: () -> Unit
    ) {
        val visual = LocalVisualSpec.current
        val hasLogin = rule.loginUrl.isNotBlank() || rule.loginUi.isNotBlank()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) },
            shape = RoundedCornerShape(10.dp),
            color = if (checked) accent.copy(alpha = 0.14f) else panelColor,
            contentColor = textColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rule.displayName(),
                    color = if (checked) accent else textColor,
                    style = visual.type.body,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasLogin) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier
                            .heightIn(min = 30.dp)
                            .clickable(onClick = onLogin),
                        shape = RoundedCornerShape(10.dp),
                        color = panelColor,
                        contentColor = accent,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.login),
                                color = accent,
                                style = visual.type.caption,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = switchCheckedTrack,
                        uncheckedThumbColor = switchUncheckedThumb,
                        uncheckedTrackColor = panelColor
                    )
                )
            }
        }
    }

    private fun updateRuleEnabled(rule: ParagraphRule, checked: Boolean, order: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (checked) {
                    appDb.paragraphRuleDao.insertBookRule(
                        BookParagraphRule(bookUrl, rule.id, true, order)
                    )
                } else {
                    appDb.paragraphRuleDao.deleteBookRule(bookUrl, rule.id)
                }
            }
            (activity as? ReadBookActivity)?.refreshParagraphRuleLayout()
        }
    }

    private fun openLogin(rule: ParagraphRule) {
        if (rule.loginUrl.isBlank() && rule.loginUi.isBlank()) {
            toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<HostSourceLoginActivity> {
            putExtra("bookType", -1)
            putExtra("type", "paragraphRule")
            putExtra("key", rule.id.toString())
            putExtra("bookUrl", bookUrl)
            putExtra("chapterIndex", ReadBook.durChapterIndex)
        }
    }

    private fun openManage() {
        startActivity<ParagraphRuleManageActivity> {
            putExtra("bookUrl", bookUrl)
        }
    }

    companion object {
        private const val ARG_BOOK_URL = "bookUrl"

        internal fun create(bookUrl: String): ParagraphRuleQuickDialog {
            return ParagraphRuleQuickDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_URL, bookUrl)
                }
            }
        }
    }
}
