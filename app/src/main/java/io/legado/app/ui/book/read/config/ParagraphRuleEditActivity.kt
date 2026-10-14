package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.association.ParagraphRuleImportPolicy
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// __CLASS__

/**
 * 段落规则编辑 V5 化。
 * 旧版 BaseActivity + ViewBinding + optionsMenu + 旧弹窗，全部替换为
 * setV5Content + HostV5Page + Compose 表单 + v5 弹窗。
 * 业务逻辑（DAO/调试/剪贴板/校验）逐行保留，仅换 UI 承载。
 */
class ParagraphRuleEditActivity : AppCompatActivity() {

    internal var meta by mutableStateOf(ParagraphRule())
    internal var name by mutableStateOf("")
    internal var loginUrl by mutableStateOf("")
    internal var loginUi by mutableStateOf("")
    internal var timeout by mutableStateOf("")
    internal var script by mutableStateOf("")
    internal var jsLib by mutableStateOf("")
    internal var enabledCookieJar by mutableStateOf(false)
    internal var loading by mutableStateOf(true)
    internal var focusedTag by mutableStateOf("script")

    internal var overflowMenuOpen by mutableStateOf(false)
    internal var debugMenuOpen by mutableStateOf(false)
    internal var debugCandidates by mutableStateOf<Pair<ParagraphRule, List<BookChapter>>?>(null)
    internal var debugResult by mutableStateOf<String?>(null)

    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra("text")?.let { text ->
                    applyFocusedEdit(text)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { ParagraphRuleEditContent(this@ParagraphRuleEditActivity) }
        val id = intent.getLongExtra("id", 0L)
        lifecycleScope.launch {
            meta = withContext(Dispatchers.IO) {
                appDb.paragraphRuleDao.get(id)
            } ?: ParagraphRule()
            if (id > 0L && !ParagraphRuleImportPolicy.isEditable(meta)) {
                toastOnUi(R.string.large_config_read_only)
                finish()
                return@launch
            }
            bindRule()
        }
    }

    private fun bindRule() {
        name = meta.name
        loginUrl = meta.loginUrl
        loginUi = meta.loginUi
        enabledCookieJar = meta.enabledCookieJar
        timeout = meta.validTimeout().toString()
        // 大字段一次性绑定；旧版分帧填充仅为避免卡顿，Compose 状态一次到位
        script = meta.script
        jsLib = meta.jsLib
        loading = false
    }

    internal fun applyFocusedEdit(text: String) {
        when (focusedTag) {
            "name" -> name = text
            "loginUrl" -> loginUrl = text
            "loginUi" -> loginUi = text
            "timeout" -> timeout = text
            "jsLib" -> jsLib = text
            else -> script = text
        }
    }

    internal fun onFullEditClicked() {
        if (loading) {
            toastOnUi(R.string.data_loading)
            return
        }
        textEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", focusedText())
            putExtra("title", hintFor(focusedTag))
        })
    }

    private fun focusedText(): String = when (focusedTag) {
        "name" -> name
        "loginUrl" -> loginUrl
        "loginUi" -> loginUi
        "timeout" -> timeout
        "jsLib" -> jsLib
        else -> script
    }

    private fun hintFor(tag: String): String? = when (tag) {
        "name" -> getString(R.string.name)
        "loginUrl" -> getString(R.string.login_url)
        "loginUi" -> getString(R.string.login_ui)
        "timeout" -> getString(R.string.timeout_millisecond)
        "script" -> getString(R.string.paragraph_rule_script)
        "jsLib" -> "jsLib"
        else -> null
    }

    internal fun debugRule() {
        val debugRule = getRule()
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val book = ReadBook.book ?: return@withContext null
                val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                book to chapters
            }
            val book = pair?.first
            val chapters = pair?.second.orEmpty()
            if (book == null) {
                toastOnUi(R.string.paragraph_rule_no_book_hint)
                return@launch
            }
            if (chapters.isEmpty()) {
                toastOnUi("No chapters")
                return@launch
            }
            val current = ReadBook.durChapterIndex
            val start = (current - 20).coerceAtLeast(0)
            val candidates = chapters.drop(start).take(80)
            debugCandidates = debugRule to candidates
            debugMenuOpen = true
        }
    }

    internal fun onDebugChapterSelected(chapter: BookChapter) {
        val debugRule = debugCandidates?.first ?: return
        debugMenuOpen = false
        runDebugRule(debugRule, ReadBook.book ?: return, chapter)
    }

    private fun runDebugRule(debugRule: ParagraphRule, book: Book, chapter: BookChapter) {
        lifecycleScope.launch {
            val message = kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val content = BookHelp.getContent(book, chapter) ?: run {
                        val source = ReadBook.bookSource
                            ?: throw IllegalStateException("No book source and no local cache")
                        WebBook.getContentAwait(source, book, chapter, needSave = false)
                    }
                    val debug = ParagraphRuleProcessor.debug(debugRule, book, chapter, content)
                    val result = debug.content
                    buildString {
                        appendLine("book: ${book.name}")
                        appendLine("chapter: ${chapter.title}")
                        appendLine("rawUrl: ${chapter.url}")
                        appendLine("absoluteUrl: ${kotlin.runCatching { chapter.getAbsoluteURL() }.getOrNull().orEmpty()}")
                        appendLine("paragraphs: ${result.split('\n').count { it.isNotBlank() }}")
                        appendLine("length: ${result.length}")
                        appendLine("imageTags: ${Regex("<img\\b", RegexOption.IGNORE_CASE).findAll(result).count()}")
                        appendLine()
                        appendLine("logs:")
                        if (debug.logs.isEmpty()) {
                            appendLine("(empty)")
                        } else {
                            debug.logs.forEach { appendLine(it) }
                        }
                        appendLine()
                        appendLine("result:")
                        append(result.take(4000))
                    }
                }
            }.getOrElse {
                "Paragraph rule debug failed:\n${it.localizedMessage ?: it}\n\n${it.stackTraceStr}"
            }
            debugResult = message
        }
    }

    internal fun pasteRule() {
        val raw = getClipText()
        val imported = GSON.fromJsonObject<ParagraphRule>(raw).getOrNull()
        if (imported == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        meta = imported.copy(id = meta.id, order = meta.order, updateTime = System.currentTimeMillis())
        bindRule()
    }

    internal fun getRule(): ParagraphRule = meta.copy(
        name = name.trim(),
        loginUrl = loginUrl,
        loginUi = loginUi,
        enabledCookieJar = enabledCookieJar,
        timeoutMillisecond = timeout.toLongOrNull() ?: 3000L,
        script = script,
        jsLib = jsLib,
        updateTime = System.currentTimeMillis()
    )

    internal fun save() {
        if (loading) {
            toastOnUi(R.string.data_loading)
            return
        }
        val edited = getRule()
        if (edited.name.isBlank() || edited.script.isBlank()) {
            toastOnUi(R.string.paragraph_rule_save_invalid)
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (edited.id == 0L) {
                    val order = (appDb.paragraphRuleDao.maxOrder() ?: 0) + 1
                    appDb.paragraphRuleDao.insert(edited.copy(order = order))
                } else {
                    appDb.paragraphRuleDao.update(edited)
                }
            }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}

@Composable
private fun ParagraphRuleEditContent(activity: ParagraphRuleEditActivity) {
    val density = LocalVisualSpec.current.density
    val enabled = !activity.loading

    HostV5Page(
        title = stringResource(R.string.paragraph_rule_edit),
        onBack = { activity.finish() },
        trailing = {
            AppFilledButton(
                text = stringResource(R.string.action_save),
                enabled = enabled,
                onClick = { activity.save() },
            )
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.overflowMenuOpen = true },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            fun tag(tagName: String) = Modifier.onFocusChanged {
                if (it.isFocused) activity.focusedTag = tagName
            }
            AppTextField(
                value = activity.name,
                onValueChange = { activity.name = it },
                modifier = tag("name"),
                placeholder = stringResource(R.string.name),
                enabled = enabled,
                singleLine = true,
            )
            AppTextField(
                value = activity.loginUrl,
                onValueChange = { activity.loginUrl = it },
                modifier = tag("loginUrl"),
                placeholder = stringResource(R.string.login_url),
                enabled = enabled,
                singleLine = false,
            )
            AppTextField(
                value = activity.loginUi,
                onValueChange = { activity.loginUi = it },
                modifier = tag("loginUi"),
                placeholder = stringResource(R.string.login_ui),
                enabled = enabled,
                singleLine = false,
            )
            AppRowWithSwitch(
                title = "启用 Cookie",
                checked = activity.enabledCookieJar,
                onCheckedChange = { activity.enabledCookieJar = it },
                card = false,
                enabled = enabled,
            )
            AppTextField(
                value = activity.timeout,
                onValueChange = { activity.timeout = it },
                modifier = tag("timeout"),
                placeholder = stringResource(R.string.timeout_millisecond),
                enabled = enabled,
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.paragraph_rule_script),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = activity.script,
                onValueChange = { activity.script = it },
                modifier = tag("script"),
                placeholder = stringResource(R.string.paragraph_rule_script),
                enabled = enabled,
                singleLine = false,
            )
            Text(
                text = "jsLib",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = activity.jsLib,
                onValueChange = { activity.jsLib = it },
                modifier = tag("jsLib"),
                placeholder = "jsLib",
                enabled = enabled,
                singleLine = false,
            )
        }
    }

    if (activity.overflowMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.paragraph_rule_edit),
            actions = listOf(
                stringResource(R.string.edit_content) to {
                    activity.overflowMenuOpen = false
                    activity.onFullEditClicked()
                },
                stringResource(R.string.debug) to {
                    activity.overflowMenuOpen = false
                    activity.debugRule()
                },
                stringResource(R.string.copy_text) to {
                    activity.overflowMenuOpen = false
                    activity.sendToClip(GSON.toJson(activity.getRule()))
                },
                stringResource(R.string.paste_rule) to {
                    activity.overflowMenuOpen = false
                    activity.pasteRule()
                },
                stringResource(R.string.help) to {
                    activity.overflowMenuOpen = false
                    activity.showHelp("paragraphRuleHelp")
                },
            ),
            onDismiss = { activity.overflowMenuOpen = false },
        )
    }

    if (activity.debugMenuOpen) {
        val candidates = activity.debugCandidates
        if (candidates != null) {
            AppFilterDialog(
                title = stringResource(R.string.debug),
                options = candidates.second.map { "${it.index + 1}. ${it.title}" },
                selectedIndex = 0,
                onSelect = { index ->
                    candidates.second.getOrNull(index)?.let { activity.onDebugChapterSelected(it) }
                },
                onDismiss = { activity.debugMenuOpen = false },
            )
        }
    }

    activity.debugResult?.let { message ->
        AppConfirmDialog(
            title = stringResource(R.string.debug),
            message = message,
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = { activity.debugResult = null },
            onDismiss = { activity.debugResult = null },
        )
    }
}