package io.legado.app.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppRuleSwitchRow
import io.legado.app.ui.widget.compose.AppRuleTextField
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import java.util.regex.PatternSyntaxException

/**
 * 正则测试界面（移植自 Legado_Max 的 RegexTestScreen，UI 换用本仓库组件体系）
 *
 * 功能：
 * - 测试正则（或纯文本）匹配：实时预览匹配位置高亮、分组信息、匹配坐标
 * - 替换预览：显示替换后的文本效果
 * - 匹配选项：忽略大小写、多行模式、点号匹配换行
 */

/** 单个匹配项：位置区间 + 匹配文本 + 分组 */
private data class MatchResultData(
    val start: Int,
    val end: Int,
    val value: String,
    val groups: List<String> = emptyList()
)

/** 完整测试结果 */
private data class TestResult(
    val success: Boolean,
    val message: String,
    val matchCount: Int = 0,
    val highlightedText: AnnotatedString? = null,
    val replacedText: String? = null,
    val matchInfo: String? = null
)

/** 匹配高亮底色（半透明黄，与正文底色叠加仍可辨识） */
private val MatchHighlightColor = Color(0x40FFEB3B)
private val SuccessColor = Color(0xFF4CAF50)

@Composable
fun RegexTestScreen(
    initialPattern: String = "",
    initialReplacement: String = "",
    initialIsRegex: Boolean = true
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()

    var pattern by remember { mutableStateOf(TextFieldValue(initialPattern)) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var replacement by remember { mutableStateOf(TextFieldValue(initialReplacement)) }

    var ignoreCase by remember { mutableStateOf(false) }
    var multiline by remember { mutableStateOf(false) }
    var dotAll by remember { mutableStateOf(false) }
    var useRegex by remember { mutableStateOf(initialIsRegex) }
    var realtimePreview by remember { mutableStateOf(true) }

    var testResult by remember { mutableStateOf<TestResult?>(null) }

    fun performTest(): TestResult {
        val patternText = pattern.text
        val inputText = input.text
        val replacementText = replacement.text
        if (inputText.isEmpty()) {
            return TestResult(false, context.getString(R.string.input_is_empty))
        }
        if (patternText.isEmpty()) {
            return TestResult(false, context.getString(R.string.pattern_empty))
        }
        if (useRegex) {
            try {
                kotlin.text.Regex(patternText, buildRegexOptions(ignoreCase, multiline, dotAll))
            } catch (e: PatternSyntaxException) {
                return TestResult(
                    false,
                    context.getString(R.string.regex_syntax_error, e.localizedMessage ?: "")
                )
            }
        }
        return try {
            val matches = findMatches(inputText, patternText, useRegex, ignoreCase, multiline, dotAll)
            if (matches.isEmpty()) {
                return TestResult(false, context.getString(R.string.no_match_found))
            }
            // 匹配位置高亮
            val highlightedText = buildAnnotatedString {
                var lastIndex = 0
                for (match in matches.sortedBy { it.start }) {
                    if (match.start > lastIndex) {
                        append(inputText.substring(lastIndex, match.start))
                    }
                    withStyle(style = SpanStyle(background = MatchHighlightColor)) {
                        append(match.value)
                    }
                    lastIndex = match.end
                }
                if (lastIndex < inputText.length) {
                    append(inputText.substring(lastIndex))
                }
            }
            // 替换预览：替换串非空时计算
            val replacedText = if (replacementText.isNotEmpty()) {
                if (useRegex) {
                    // 转义 $ 避免 Illegal group reference，$1 等分组引用需用户显式使用
                    val escaped = replacementText.replace("$", "\\$")
                    inputText.replace(
                        kotlin.text.Regex(patternText, buildRegexOptions(ignoreCase, multiline, dotAll)),
                        escaped
                    )
                } else {
                    inputText.replace(patternText, replacementText)
                }
            } else null
            // 匹配详情：次数 + 最多 10 处的坐标
            val infoBuilder = StringBuilder()
            infoBuilder.append(context.getString(R.string.match_count_format, matches.size))
            matches.take(10).forEachIndexed { index, match ->
                infoBuilder.append("\n").append(
                    context.getString(R.string.match_position, index + 1, match.start, match.end)
                )
            }
            if (matches.size > 10) {
                infoBuilder.append("\n...").append(
                    context.getString(R.string.more_matches, matches.size - 10)
                )
            }
            TestResult(
                success = true,
                message = context.getString(R.string.regex_match_success),
                matchCount = matches.size,
                highlightedText = highlightedText,
                replacedText = replacedText,
                matchInfo = infoBuilder.toString()
            )
        } catch (e: Exception) {
            TestResult(false, e.message ?: context.getString(R.string.no_match_found))
        }
    }

    // 实时预览：相关状态变化后轻微防抖再执行
    LaunchedEffect(
        pattern, input, replacement, ignoreCase, multiline, dotAll, useRegex, realtimePreview
    ) {
        if (realtimePreview && pattern.text.isNotEmpty() && input.text.isNotEmpty()) {
            delay(200)
            testResult = performTest()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ========== 正则表达式 ==========
        ResultSectionCard(style = style) {
            AppRuleTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = stringResource(R.string.regex_test_pattern),
                minLines = 2,
                maxLines = 4
            )
            Spacer(modifier = Modifier.height(8.dp))
            AppRuleSwitchRow(
                text = stringResource(R.string.use_regex),
                checked = useRegex,
                onCheckedChange = { useRegex = it }
            )
            if (useRegex) {
                Spacer(modifier = Modifier.height(6.dp))
                AppRuleSwitchRow(
                    text = stringResource(R.string.ignore_case),
                    checked = ignoreCase,
                    onCheckedChange = { ignoreCase = it }
                )
                AppRuleSwitchRow(
                    text = stringResource(R.string.multiline_mode),
                    checked = multiline,
                    onCheckedChange = { multiline = it }
                )
                AppRuleSwitchRow(
                    text = stringResource(R.string.dot_matches_newline),
                    checked = dotAll,
                    onCheckedChange = { dotAll = it }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            AppRuleSwitchRow(
                text = stringResource(R.string.realtime_preview),
                checked = realtimePreview,
                onCheckedChange = { realtimePreview = it }
            )
        }

        // ========== 替换为 ==========
        ResultSectionCard(style = style) {
            AppRuleTextField(
                value = replacement,
                onValueChange = { replacement = it },
                label = stringResource(R.string.replace_to),
                minLines = 2,
                maxLines = 4
            )
        }

        // ========== 待测试文本 ==========
        ResultSectionCard(style = style) {
            AppRuleTextField(
                value = input,
                onValueChange = { input = it },
                label = stringResource(R.string.regex_test_input),
                minLines = 4,
                maxLines = 10
            )
        }

        // ========== 操作按钮（仅手动模式显示） ==========
        if (!realtimePreview) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.clear),
                    palette = style.toMiuixPalette(),
                    onClick = {
                        pattern = TextFieldValue("")
                        input = TextFieldValue("")
                        replacement = TextFieldValue("")
                        testResult = null
                    }
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.regex_test_run),
                    palette = style.toMiuixPalette(),
                    primary = true,
                    onClick = {
                        if (pattern.text.isEmpty()) {
                            context.toastOnUi(R.string.pattern_empty)
                            return@LegadoMiuixActionButton
                        }
                        if (input.text.isEmpty()) {
                            context.toastOnUi(R.string.input_is_empty)
                            return@LegadoMiuixActionButton
                        }
                        testResult = performTest()
                    }
                )
            }
        }

        // ========== 结果区 ==========
        testResult?.let { result ->
            // 状态提示
            val statusColor = if (result.success) SuccessColor else style.danger
            Surface(
                color = statusColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(style.actionRadius),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(13.dp)) {
                    Text(
                        text = result.message,
                        color = statusColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (result.success && result.matchCount > 0) {
                        Text(
                            text = stringResource(R.string.match_count_format, result.matchCount),
                            color = statusColor.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
            // 匹配详情
            result.matchInfo?.let { info ->
                ResultSectionCard(style = style, title = stringResource(R.string.match_detail)) {
                    Text(text = info, color = style.secondaryText, fontSize = 12.sp)
                }
            }
            // 匹配高亮
            result.highlightedText?.let { highlighted ->
                ResultSectionCard(style = style, title = stringResource(R.string.regex_highlight)) {
                    SelectionContainer {
                        Text(text = highlighted, color = style.primaryText, fontSize = 13.sp)
                    }
                }
            }
            // 替换预览
            result.replacedText?.let { replaced ->
                ResultSectionCard(style = style, title = stringResource(R.string.replace_preview)) {
                    SelectionContainer {
                        Text(text = replaced, color = style.primaryText, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 结果分区卡片，带可选标题 */
@Composable
private fun ResultSectionCard(
    style: io.legado.app.ui.widget.compose.AppDialogStyle,
    title: String? = null,
    content: @Composable () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(13.dp)
    ) {
        title?.let {
            Text(
                text = it,
                color = style.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        content()
    }
}

private fun buildRegexOptions(
    ignoreCase: Boolean,
    multiline: Boolean,
    dotAll: Boolean
): Set<RegexOption> {
    val options = mutableSetOf<RegexOption>()
    if (ignoreCase) options.add(RegexOption.IGNORE_CASE)
    if (multiline) options.add(RegexOption.MULTILINE)
    if (dotAll) options.add(RegexOption.DOT_MATCHES_ALL)
    return options
}

/** 查找全部匹配项：正则用 findAll，纯文本用 indexOf 循环 */
private fun findMatches(
    input: String,
    pattern: String,
    useRegex: Boolean,
    ignoreCase: Boolean,
    multiline: Boolean,
    dotAll: Boolean
): List<MatchResultData> {
    val results = mutableListOf<MatchResultData>()
    if (useRegex) {
        val regex = kotlin.text.Regex(pattern, buildRegexOptions(ignoreCase, multiline, dotAll))
        regex.findAll(input).forEach { matchResult ->
            results.add(
                MatchResultData(
                    start = matchResult.range.first,
                    end = matchResult.range.last + 1,
                    value = matchResult.value,
                    groups = matchResult.groupValues.drop(1)
                )
            )
        }
    } else {
        var startIndex = 0
        while (true) {
            val index = input.indexOf(pattern, startIndex)
            if (index == -1) break
            results.add(MatchResultData(index, index + pattern.length, pattern))
            startIndex = index + pattern.length
        }
    }
    return results
}
