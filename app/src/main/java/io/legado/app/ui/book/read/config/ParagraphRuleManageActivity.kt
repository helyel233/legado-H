package io.legado.app.ui.book.read.config

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.gson.reflect.TypeToken
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookParagraphRule
import io.legado.app.data.entities.ParagraphRule
import io.legado.app.data.entities.ParagraphRuleVar
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.importHttpClient as okHttpClient
import io.legado.app.model.ReadBook
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.association.ParagraphRuleImportPolicy
import io.legado.app.ui.association.ParagraphRulePackageParser
import io.legado.app.ui.association.readLimitedImportText
import io.legado.app.ui.host.HostSourceLoginActivity
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppCard
import io.legado.app.uikit.v5.components.AppChip
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppEmpty
import io.legado.app.uikit.v5.components.AppFastScroller
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppReorderableItem
import io.legado.app.uikit.v5.components.rememberAppReorderableListState
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.readBytes
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P4-3：段落规则管理 V5 化（2026-10-14）。
 * 旧版 BaseActivity + RecyclerView + ItemTouchHelper 拖拽 + 多个旧主题桥弹窗，
 * 全部替换为 setV5Content + HostV5Page + AppReorderableItem 拖拽 + v5 弹窗。
 * 业务逻辑（DAO/导入/导出/变量/删除/排序持久化）逐行保留，仅换 UI 承载。
 */
class ParagraphRuleManageActivity : AppCompatActivity() {

    internal val rulesState = mutableStateOf<List<ParagraphRule>>(emptyList())
    internal val enabledIdsState = mutableStateOf<Set<Long>>(emptySet())
    internal var summaryText by mutableStateOf("")
    internal var addMenuOpen by mutableStateOf(false)
    internal var importMenuOpen by mutableStateOf(false)
    internal var itemMenuTarget by mutableStateOf<ParagraphRule?>(null)
    internal var confirmDeleteTarget by mutableStateOf<ParagraphRule?>(null)
    internal var importUrlOpen by mutableStateOf(false)
    internal var importUrlText by mutableStateOf("")
    internal var varsTarget by mutableStateOf<ParagraphRule?>(null)
    internal var varsText by mutableStateOf("")
    internal var exportUrl by mutableStateOf<String?>(null)

    private var bookUrl: String? = null
    private var refreshOnResume = false

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            lifecycleScope.launch {
                kotlin.runCatching {
                    val bytes = uri.readBytes(
                        this@ParagraphRuleManageActivity,
                        ParagraphRuleImportPolicy.MAX_PACKAGE_BYTES
                    )
                    parseImportedRules(bytes.toString(Charsets.UTF_8))
                }.onSuccess { rules ->
                    if (rules.isEmpty()) {
                        toastOnUi(R.string.wrong_format)
                    } else {
                        withContext(Dispatchers.IO) { insertImportedRules(rules) }
                        load()
                        toastOnUi(R.string.success)
                    }
                }.onFailure {
                    toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
                }
            }
        }
    }

    private val exportRuleResult = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            val url = uri.toString()
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                exportUrl = url
            } else {
                toastOnUi(R.string.export_success)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookUrl = intent.getStringExtra("bookUrl") ?: ReadBook.book?.bookUrl
        setV5Content { ParagraphRuleManageScreen(this) }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (refreshOnResume) {
            refreshOnResume = false
            refreshReadBookIfNeeded()
        }
        load()
    }

    internal fun launchImportFile() {
        importDoc.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.import_str)
            allowExtensions = arrayOf("json")
        }
    }

    internal fun showImportUrlDialog() {
        importUrlText = ""
        importUrlOpen = true
    }

    internal fun importRulesFromUrl(url: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val text = withContext(Dispatchers.IO) {
                    okHttpClient.newCallResponseBody { url(url) }.use {
                        it.readLimitedImportText(ParagraphRuleImportPolicy.MAX_PACKAGE_BYTES)
                    }
                }
                parseImportedRules(text)
            }.onSuccess { rules ->
                if (rules.isEmpty()) {
                    toastOnUi(R.string.wrong_format)
                } else {
                    withContext(Dispatchers.IO) { insertImportedRules(rules) }
                    load()
                    toastOnUi(R.string.success)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) {
                bookUrl?.let { enabledIdsState.value = appDb.paragraphRuleDao.enabledRuleIdsForBook(it).toSet() }
                appDb.paragraphRuleDao.all()
            }
            rulesState.value = rules
            summaryText = when {
                bookUrl == null -> getString(R.string.paragraph_rule_no_book_hint)
                rules.isEmpty() -> getString(R.string.paragraph_rule_empty)
                else -> getString(R.string.paragraph_rule_manage_summary)
            }
        }
    }

    internal fun toggle(rule: ParagraphRule, enable: Boolean) {
        val url = bookUrl
        if (url == null) {
            toastOnUi(R.string.paragraph_rule_no_book_hint)
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (enable) {
                    appDb.paragraphRuleDao.insertBookRule(
                        BookParagraphRule(url, rule.id, true, rulesState.value.indexOfFirst { it.id == rule.id })
                    )
                } else {
                    appDb.paragraphRuleDao.deleteBookRule(url, rule.id)
                }
            }
            refreshReadBookIfNeeded()
            load()
        }
    }

    internal fun login(rule: ParagraphRule) {
        refreshOnResume = true
        startActivity<HostSourceLoginActivity> {
            putExtra("bookType", -1)
            putExtra("type", "paragraphRule")
            putExtra("key", rule.id.toString())
            bookUrl?.let { putExtra("bookUrl", it) }
            putExtra("chapterIndex", ReadBook.durChapterIndex)
        }
    }

    internal fun openEditRule(ruleId: Long? = null) {
        refreshOnResume = true
        startActivity<ParagraphRuleEditActivity> {
            ruleId?.let { putExtra("id", it) }
        }
    }

    internal fun copyRule(rule: ParagraphRule) {
        sendToClip(serializeRule(rule))
    }

    internal fun editVars(rule: ParagraphRule) {
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                GSON.toJson(appDb.paragraphRuleDao.vars(rule.id).associate { it.name to it.value })
            }
            varsTarget = rule
            varsText = raw
        }
    }

    internal fun saveVars(ruleId: Long, raw: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val values: Map<String, String> = if (raw.isBlank()) emptyMap() else GSON.fromJson(raw, type)
                withContext(Dispatchers.IO) {
                    appDb.paragraphRuleDao.deleteVars(ruleId)
                    values.forEach { (key, value) ->
                        appDb.paragraphRuleDao.putVar(ParagraphRuleVar(ruleId, key, value))
                    }
                }
            }.onSuccess {
                toastOnUi(R.string.success)
                refreshReadBookIfNeeded()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.wrong_format))
            }
        }
    }

    private fun parseImportedRules(raw: String): List<ParagraphRule> {
        return ParagraphRulePackageParser.parse(raw).entries.map { it.rule }
    }

    private fun insertImportedRules(rules: List<ParagraphRule>) {
        var order = (appDb.paragraphRuleDao.maxOrder() ?: 0) + 1
        rules.forEach { imported ->
            val rule = imported.copy(id = 0L, order = order++, updateTime = System.currentTimeMillis())
            appDb.paragraphRuleDao.insert(rule)
        }
    }

    internal fun exportRule(rule: ParagraphRule) {
        exportRuleResult.launch {
            mode = HandleFileContract.EXPORT
            fileData = HandleFileContract.FileData(
                "paragraphRule-${rule.displayName()}.json",
                serializeRule(rule).toByteArray(),
                "application/json"
            )
        }
    }

    private fun serializeRule(rule: ParagraphRule): String = GSON.toJson(rule)

    internal fun deleteRule(rule: ParagraphRule) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { appDb.paragraphRuleDao.deleteWithRelations(rule) }
            refreshReadBookIfNeeded()
            load()
        }
    }

    internal fun persistOrder(rules: List<ParagraphRule>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                rules.forEachIndexed { index, rule ->
                    if (rule.order != index) {
                        appDb.paragraphRuleDao.update(rule.copy(order = index, updateTime = System.currentTimeMillis()))
                    }
                    bookUrl?.let { url ->
                        if (enabledIdsState.value.contains(rule.id)) {
                            appDb.paragraphRuleDao.insertBookRule(BookParagraphRule(url, rule.id, true, index))
                        }
                    }
                }
            }
            refreshReadBookIfNeeded()
            load()
        }
    }

    private fun refreshReadBookIfNeeded() {
        if (bookUrl != ReadBook.book?.bookUrl) return
        ReadBook.invalidateParagraphRuleLayout()
        ReadBook.callBack?.upContent(resetPageOffset = false)
        ReadBook.loadContent(resetPageOffset = false)
    }
}

private enum class Action(val titleRes: Int) {
    EDIT(R.string.edit),
    EXPORT(R.string.export_str),
    COPY(R.string.copy_rule),
    VARS(R.string.paragraph_rule_vars),
    DELETE(R.string.delete)
}

@Composable
private fun ParagraphRuleManageScreen(activity: ParagraphRuleManageActivity) {
    val visual = LocalVisualSpec.current
    val density = visual.density
    val colors = LocalSemanticColors.current

    val rules by activity.rulesState
    val enabledIds by activity.enabledIdsState

    var orderedRules by remember(rules) { mutableStateOf(rules) }
    val listState = rememberLazyListState()
    val reorderState = rememberAppReorderableListState(
        lazyListState = listState,
        onMove = { from, to ->
            orderedRules = orderedRules.toMutableList().apply { add(to, removeAt(from)) }
        },
    )

    HostV5Page(
        title = stringResource(R.string.paragraph_rule_manage),
        onBack = { activity.finish() },
        trailing = {
            AppOverflowMenuButton(contentDescription = stringResource(R.string.more)) {
                activity.importMenuOpen = true
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = activity.summaryText,
                style = visual.type.caption,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = density.screenPadding,
                    vertical = density.blockGap / 2,
                ),
            )
            if (rules.isEmpty()) {
                AppEmpty(
                    title = stringResource(R.string.paragraph_rule_manage),
                    supporting = stringResource(R.string.paragraph_rule_empty),
                )
            } else {
                AppFastScroller(
                    listState = listState,
                    topContentPadding = density.blockGap / 2,
                    bottomContentPadding = density.screenPadding,
                    endContentPadding = density.screenPadding,
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = density.screenPadding,
                            end = density.screenPadding,
                            top = density.blockGap / 2,
                            bottom = density.screenPadding,
                        ),
                        verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                    ) {
                        items(orderedRules, key = { it.id }) { rule ->
                            val enabled = rule.id in enabledIds
                            AppReorderableItem(
                                reorderState,
                                key = rule.id,
                                onDragStopped = { activity.persistOrder(orderedRules) },
                            ) { _, dragHandle ->
                                ParagraphRuleRow(
                                    rule = rule,
                                    enabled = enabled,
                                    dragHandle = dragHandle,
                                    onToggle = { activity.toggle(rule, !enabled) },
                                    onLogin = { activity.login(rule) },
                                    onMore = { activity.itemMenuTarget = rule },
                                    onRowClick = { activity.itemMenuTarget = rule },
                                )
                            }
                        }
                    }
                }
            }
            AppFilledButton(
                text = stringResource(R.string.add),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = density.screenPadding, vertical = density.blockGap),
                onClick = { activity.addMenuOpen = true },
            )
        }
    }

    // 底部「添加」菜单
    if (activity.addMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.paragraph_rule_manage),
            actions = listOf(
                stringResource(R.string.add) to { activity.openEditRule() },
                stringResource(R.string.import_str) to { activity.launchImportFile() },
                stringResource(R.string.import_on_line) to { activity.showImportUrlDialog() },
            ),
            onDismiss = { activity.addMenuOpen = false },
        )
    }

    // 顶栏「导入」菜单
    if (activity.importMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.import_str),
            actions = listOf(
                stringResource(R.string.import_str) to { activity.launchImportFile() },
                stringResource(R.string.import_on_line) to { activity.showImportUrlDialog() },
                stringResource(R.string.help) to { activity.showHelp("paragraphRuleHelp") },
            ),
            onDismiss = { activity.importMenuOpen = false },
        )
    }

    // 条目动作菜单
    activity.itemMenuTarget?.let { rule ->
        val editable = ParagraphRuleImportPolicy.isEditable(rule)
        val actions = buildList {
            if (editable) add(Action.EDIT)
            add(Action.EXPORT)
            if (editable) {
                add(Action.COPY)
                add(Action.VARS)
            }
            add(Action.DELETE)
        }
        val title = if (editable) rule.displayName() else "${rule.displayName()} · ${stringResource(R.string.read_only)}"
        AppMenuSheet(
            title = title,
            actions = actions.map { action ->
                stringResource(action.titleRes) to {
                    when (action) {
                        Action.EDIT -> activity.openEditRule(rule.id)
                        Action.EXPORT -> activity.exportRule(rule)
                        Action.COPY -> activity.copyRule(rule)
                        Action.VARS -> activity.editVars(rule)
                        Action.DELETE -> activity.confirmDeleteTarget = rule
                    }
                }
            },
            onDismiss = { activity.itemMenuTarget = null },
        )
    }

    // 删除确认
    activity.confirmDeleteTarget?.let { rule ->
        AppConfirmDialog(
            title = stringResource(R.string.draw),
            message = stringResource(R.string.sure_del) + "\n" + rule.displayName(),
            confirmLabel = stringResource(R.string.yes),
            cancelLabel = stringResource(R.string.no),
            danger = true,
            onConfirm = {
                activity.confirmDeleteTarget = null
                activity.deleteRule(rule)
            },
            onDismiss = { activity.confirmDeleteTarget = null },
        )
    }

    // 在线导入 URL 输入
    if (activity.importUrlOpen) {
        AppInputDialog(
            title = stringResource(R.string.import_on_line),
            value = activity.importUrlText,
            onValueChange = { activity.importUrlText = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = "https://...",
            onConfirm = { text ->
                activity.importUrlOpen = false
                if (text.isNotBlank()) activity.importRulesFromUrl(text)
            },
            onDismiss = { activity.importUrlOpen = false },
        )
    }

    // 变量 JSON 编辑
    activity.varsTarget?.let { rule ->
        AppInputDialog(
            title = stringResource(R.string.paragraph_rule_vars),
            value = activity.varsText,
            onValueChange = { activity.varsText = it },
            confirmLabel = stringResource(R.string.ok),
            cancelLabel = stringResource(R.string.cancel),
            placeholder = stringResource(R.string.paragraph_rule_vars_json),
            onConfirm = { text ->
                activity.varsTarget = null
                activity.saveVars(rule.id, text)
            },
            onDismiss = { activity.varsTarget = null },
        )
    }

    // 导出 URL 复制提示
    activity.exportUrl?.let { url ->
        val context = LocalContext.current
        AppConfirmDialog(
            title = stringResource(R.string.upload_url),
            message = url,
            confirmLabel = stringResource(R.string.copy_text),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                activity.exportUrl = null
                context.sendToClip(url)
            },
            onDismiss = { activity.exportUrl = null },
        )
    }
}

@Composable
private fun ParagraphRuleRow(
    rule: ParagraphRule,
    enabled: Boolean,
    dragHandle: Modifier,
    onToggle: () -> Unit,
    onLogin: () -> Unit,
    onMore: () -> Unit,
    onRowClick: () -> Unit,
) {
    val visual = LocalVisualSpec.current
    val colors = LocalSemanticColors.current
    val density = visual.density
    AppCard(onClick = onRowClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = density.cardPadding, vertical = density.cardPadding / 1.4f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(density.blockGap / 1.5f),
        ) {
            Box(
                modifier = dragHandle.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("≡", style = visual.type.caption, color = colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.displayName(),
                    style = visual.type.body,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (enabled) R.string.paragraph_rule_enabled_for_book
                        else R.string.paragraph_rule_disabled_for_book
                    ),
                    style = visual.type.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2),
            ) {
                AppChip(
                    text = stringResource(if (enabled) R.string.disable else R.string.enable),
                    selected = enabled,
                    onClick = onToggle,
                )
                AppChip(text = stringResource(R.string.login), selected = false, onClick = onLogin)
                AppOverflowMenuButton(contentDescription = stringResource(R.string.more), onClick = onMore)
            }
        }
    }
}
