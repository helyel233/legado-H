package io.legado.app.ui.code

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.util.regex.RegexBackrefGrammar
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.config.ChangeThemeDialog
import io.legado.app.ui.code.config.SettingsDialog
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.AppSurface
import io.legado.app.uikit.v5.token.MaterialLevel
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp

class CodeEditActivity : AppCompatActivity(),
    ChangeThemeDialog.CallBack, SettingsDialog.CallBack {
    companion object {
        private var isInitialized = false
        private var findText = ""
        private var replaceText = ""
        private var isRegex = true
    }

    private val viewModel by viewModels<CodeEditViewModel>()

    private lateinit var editor: CodeEditor
    private val editorSearcher: EditorSearcher get() = editor.searcher
    private var searchOptions: SearchOptions? = null

    private val isDark
        get() = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    // ---------- Compose 状态 ----------
    var titleText by mutableStateOf("")
    var writable by mutableStateOf(true)
    var autoWrap by mutableStateOf(AppConfig.editAutoWrap)
    var menuOpen by mutableStateOf(false)
    var searchVisible by mutableStateOf(false)
    var findValue by mutableStateOf("")
    var replaceValue by mutableStateOf("")
    var replaceExpanded by mutableStateOf(false)
    var regexOn by mutableStateOf(true)
    var searchResultText by mutableStateOf("0")

    private var receiptSearch: SubscriptionReceipt<PublishSearchResultEvent>? = null
    private var receiptChange: SubscriptionReceipt<SelectionChangeEvent>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = CodeEditor(this).apply {
            setTextSize(18f)
        }
        // 原 initTheme 逻辑
        if (!isInitialized) {
            viewModel.initSora()
            isInitialized = true
        }
        editor.colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance()) //先设置颜色,避免一开始的白屏
        val index = if (isDark) {
            AppConfig.editThemeDark
        } else {
            AppConfig.editTheme
        }
        upTheme(index)
        themeIndex = index

        setV5Content { CodeEditScreen(this) }

        viewModel.initData(intent) {
            editor.apply {
                viewModel.title?.let {
                    titleText = it
                }
                // IME 组合态和提交态同时触发配对时会出现 `())` 这类重复闭合
                getProps().autoCompletionOnComposing = false
                nonPrintablePaintingFlags = AppConfig.editNonPrintable
                setEditorLanguage(viewModel.language)
                upEdit(AppConfig.editFontScale, null, AppConfig.editAutoWrap)
                setText(viewModel.initialText)
                editable = viewModel.writable
                writable = viewModel.writable
                requestFocus()
                postDelayed({
                    val pos = cursor.indexer.getCharPosition(viewModel.cursorPosition)
                    setSelection(pos.line, pos.column, true)
                }, 360) // 进行延时,确保加载渲染完成,从而确保光标能显示跳转到长文本最后
            }
        }
    }

    internal fun createEditorContainer(context: Context): FrameLayout {
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            (editor.parent as? ViewGroup)?.removeView(editor)
            addView(
                editor,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    internal fun releaseEditorContainer(container: FrameLayout) {
        // 只摘除宿主，不在此 release：editor 统一在 onDestroy 里 release
        container.removeAllViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        editorSearcher.stopSearch()
        editor.release()
    }

    /**
     * 使用super.finish(),防止循环回调
     * */
    internal fun save(check: Boolean) {
        if (!viewModel.writable) return super.finish()
        val text = editor.text.toString()
        val cursorPos = editor.cursor?.left ?: 0
        when {
            text == viewModel.initialText -> {
                if (cursorPos > 0) {
                    val result = Intent().apply {
                        putExtra("cursorPosition", cursorPos)
                    }
                    setResult(RESULT_OK, result)
                }
                super.finish()
            }
            check -> {
                alert(R.string.exit) {
                    setMessage(R.string.exit_no_save)
                    positiveButton(R.string.yes)
                    negativeButton(R.string.no) {
                        if (cursorPos > 0) {
                            val result = Intent().apply {
                                putExtra("cursorPosition", cursorPos)
                            }
                            setResult(RESULT_OK, result)
                        }
                        super.finish()
                    }
                }
            }
            else -> {
                val result = Intent().apply {
                    // Keep large payloads out of Binder; some ROMs fail well under 1MB.
                    val bytes = text.toByteArray(Charsets.UTF_8).size
                    if (bytes > 100_000) {
                        val key = "code_edit_result_" + System.nanoTime()
                        CacheManager.putMemory(key, text)
                        putExtra("cacheKey", key)
                    } else {
                        putExtra("text", text)
                    }
                    putExtra("cursorPosition", cursorPos)
                }
                setResult(RESULT_OK, result)
                super.finish()
            }
        }
    }

    override fun upEdit(fontSize: Int?, autoComplete: Boolean?, autoWarp: Boolean?, editNonPrintable: Int?) {
        if (fontSize != null) {
            editor.setTextSize(fontSize.toFloat())
        }
        if (autoComplete != null) {
            viewModel.language?.isAutoCompleteEnabled = autoComplete
            editor.setEditorLanguage(viewModel.language)
        }
        if (autoWarp != null) {
            editor.isWordwrap = autoWarp
        }
        if (editNonPrintable != null) {
            editor.nonPrintablePaintingFlags = editNonPrintable
        }
    }

    override fun upTheme(index: Int) {
        if (themeIndex != index) {
            viewModel.loadTextMateThemes(index)
            editor.setEditorLanguage(viewModel.language) //每次更改颜色后需要再执行一次语言设置,防止切换主题后高亮颜色不正确
            themeIndex = index
        }
    }

    internal fun buildMenuActions(): List<Pair<String, () -> Unit>> {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(getString(R.string.search) to { openSearch() })
        actions.add(getString(R.string.format_code) to { viewModel.formatCode(editor) })
        actions.add(getString(R.string.change_theme) to { showDialogFragment(ChangeThemeDialog()) })
        actions.add(getString(R.string.config_settings) to { showDialogFragment(SettingsDialog(this, this)) })
        val autoWrapLabel = getString(R.string.auto_wrap)
        actions.add((if (autoWrap) "✓ " else "") + autoWrapLabel to { toggleAutoWrap() })
        actions.add(getString(R.string.log) to { showDialogFragment<AppLogDialog>() })
        // 帮助（旧 BaseActivity helpActions）
        actions.add("书源教程" to { showHelp("ruleHelp") })
        actions.add("订阅源教程" to { showHelp("rssRuleHelp") })
        actions.add("js教程" to { showHelp("jsHelp") })
        actions.add("正则教程" to { showHelp("regexHelp") })
        return actions
    }

    private fun toggleAutoWrap() {
        val newValue = !AppConfig.editAutoWrap
        upEdit(autoWarp = newValue)
        putPrefBoolean(PreferKey.editAutoWrap, newValue)
        autoWrap = newValue
    }

    private fun setSearchOptions() {
        searchOptions =  SearchOptions(
            if (isRegex) SearchOptions.TYPE_REGULAR_EXPRESSION else SearchOptions.TYPE_NORMAL,
            !isRegex,
            RegexBackrefGrammar.DEFAULT
        )
    }

    private fun openSearch() {
        if (searchVisible) return
        searchVisible = true
        regexOn = isRegex
        setSearchOptions()
        receiptSearch = editor.subscribeEvent(PublishSearchResultEvent::class.java) { event, _ ->
            if (event.editor == editor) {
                updateSearchResults()
            }
        }
        receiptChange = editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            if (event.cause == SelectionChangeEvent.CAUSE_SEARCH) {
                updateSearchResults()
            }
        }
        findValue = findText
        replaceValue = replaceText
        searchTxt(findText)
    }

    internal fun onFindChange(value: String) {
        findValue = value
        if (value.isNotEmpty()) {
            findText = value
            searchTxt(value)
        } else {
            findText = ""
            editorSearcher.stopSearch()
            editor.invalidate()
        }
    }

    internal fun onReplaceChange(value: String) {
        replaceValue = value
        if (value.isNotEmpty()) {
            replaceText = value
        }
    }

    internal fun onRegexChange(checked: Boolean) {
        isRegex = checked
        regexOn = checked
        setSearchOptions()
        searchTxt(findValue)
    }

    internal fun closeSearch() {
        searchVisible = false
        replaceExpanded = false
        editorSearcher.stopSearch()
        receiptSearch?.unsubscribe()
        receiptChange?.unsubscribe()
        receiptSearch = null
        receiptChange = null
        editor.requestFocus()
        editor.invalidate()
    }

    internal fun gotoPrevious() {
        if (editorSearcher.hasQuery()) {
            editorSearcher.gotoPrevious()
        }
    }

    internal fun gotoNext() {
        if (editorSearcher.hasQuery()) {
            editorSearcher.gotoNext()
        }
    }

    internal fun onReplaceClick() {
        if (!replaceExpanded) {
            replaceExpanded = true
        } else {
            if (editorSearcher.hasQuery()) {
                editorSearcher.replaceCurrentMatch(replaceValue)
            }
        }
    }

    internal fun closeReplace() {
        replaceExpanded = false
    }

    internal fun replaceAll() {
        if (editorSearcher.hasQuery()) {
            editorSearcher.replaceAll(replaceValue)
        }
    }

    private fun searchTxt(txt: String) {
        if (txt.isNotEmpty()) {
            try {
                searchOptions?.let {
                    editorSearcher.search(txt, it)
                }
            } catch (_: java.util.regex.PatternSyntaxException) {
                // 忽略正则表达式语法错误
                editorSearcher.stopSearch()
                editor.invalidate()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSearchResults() {
        if (editorSearcher.hasQuery()) {
            val totalResults = editorSearcher.matchedPositionCount
            val currentPosition = editorSearcher.currentMatchedPositionIndex + 1
            searchResultText =
                "${if (currentPosition > 0) "$currentPosition/" else ""}$totalResults"
        }
    }

    override fun finish() {
        save(true)
    }
}

@Composable
private fun CodeEditScreen(activity: CodeEditActivity) {
    val context = LocalContext.current
    val editorContainer = remember { activity.createEditorContainer(context) }

    DisposableEffect(Unit) {
        onDispose { activity.releaseEditorContainer(editorContainer) }
    }

    // 搜索面板打开时，返回键先关搜索，否则走保存流程
    BackHandler(enabled = activity.searchVisible) { activity.closeSearch() }

    HostV5Page(
        title = activity.titleText,
        onBack = { activity.finish() },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppOverflowMenuButton(
                    contentDescription = context.getString(R.string.more),
                    onClick = { activity.menuOpen = true },
                )
                if (activity.writable) {
                    AppFilledButton(
                        text = context.getString(R.string.action_save),
                        onClick = { activity.save(false) },
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    factory = { editorContainer },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (activity.searchVisible) {
                CodeEditSearchBar(activity)
            }
        }
    }

    if (activity.menuOpen) {
        AppMenuSheet(
            title = activity.titleText,
            actions = activity.buildMenuActions(),
            onDismiss = { activity.menuOpen = false },
        )
    }
}

@Composable
private fun CodeEditSearchBar(activity: CodeEditActivity) {
    AppSurface(
        level = MaterialLevel.FLOATING,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.search_result))
                Text(activity.searchResultText)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.regex))
                AppSwitch(
                    checked = activity.regexOn,
                    onCheckedChange = { activity.onRegexChange(it) },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.find))
                AppTextField(
                    value = activity.findValue,
                    onValueChange = { activity.onFindChange(it) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { activity.closeSearch() }) { Text("×") }
            }
            if (activity.replaceExpanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.replace))
                    AppTextField(
                        value = activity.replaceValue,
                        onValueChange = { activity.onReplaceChange(it) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { activity.closeReplace() }) { Text("×") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { activity.gotoPrevious() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_previous))
                }
                TextButton(onClick = { activity.gotoNext() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_next))
                }
                TextButton(onClick = { activity.onReplaceClick() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.replace))
                }
                TextButton(onClick = { activity.replaceAll() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.replace_all))
                }
            }
        }
    }
}
