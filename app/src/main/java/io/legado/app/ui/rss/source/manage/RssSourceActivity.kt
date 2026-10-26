package io.legado.app.ui.rss.source.manage

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityRssSourceBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.model.CheckRssSource
import io.legado.app.model.Debug
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.showShibbolethDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.article.RssSortActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.replaceByIndex
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeSuggestionTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.undoSnackbar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 订阅源管理
 */
class RssSourceActivity : VMBaseActivity<ActivityRssSourceBinding, RssSourceViewModel>(),
    MenuItem.OnMenuItemClickListener,
    SelectActionBar.CallBack {

    override val binding by viewBinding(ActivityRssSourceBinding::inflate)
    override val viewModel by viewModels<RssSourceViewModel>()
    private val importRecordKey = "rssSourceRecordKey"
    private var sourceFlowJob: Job? = null
    private var groups = arrayListOf<String>()
    private var groupMenu: SubMenu? = null
    private val sourcesState = mutableStateListOf<RssSource>()
    private val selectedUrls = mutableStateOf<Set<String>>(emptySet())
    private val isSelectMode = mutableStateOf(false)
    private val searchQueryState = mutableStateOf("")
    private val debugMessagesState = mutableStateMapOf<String, String>()
    private val isCheckingState = mutableStateOf(false)
    private var checkMessageRefreshJob: Job? = null
    private var checkSnackBar: Snackbar? = null
    private var checkTotal = 0
    // 只认 check() 写出的终态文案：宽泛的「成功|失败」会把中间消息
    // （如 Rss 解析链路的「≡获取成功:…」）误判成已完成，进度虚高
    private val finalMessageRegex = Regex("校验成功|校验失败")
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportRssSourceDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportRssSourceDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = if (url.isAbsUrl()) {
                    url + "\n" + DirectLinkUpload.getSummary()
                } else {
                    url
                },
                positiveText = getString(R.string.copy_text),
                negativeText = getString(R.string.cancel),
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(url) },
                onPositive = { sendToClip(url) },
                onNeutral = { showShibbolethDialog(url, ShibbolethCodec.RSS_SOURCE) }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initGroupFlow()
        upSourceFlow()
    }

    private fun initComposeContent() {
        binding.titleBar.visibility = View.GONE
        binding.selectActionBar.visibility = View.GONE
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                AppManagementScaffold(
                    title = getString(R.string.rss_source_manage),
                    selectedCount = selectedUrls.value.size,
                    totalCount = sourcesState.size,
                    searchQuery = searchQueryState.value,
                    searchHint = getString(R.string.search_rss_source),
                    onSearchChange = ::updateSearchQuery,
                    topActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.menu_action_group),
                            iconRes = R.drawable.ic_groups,
                            onClick = ::showFilterMenu
                        ),
                        AppManagementAction(
                            text = getString(R.string.add),
                            iconRes = R.drawable.ic_add,
                            onClick = { startActivity<RssSourceEditActivity>() }
                        ),
                        AppManagementAction(
                            text = getString(R.string.more_menu),
                            iconRes = R.drawable.ic_more_vert,
                            menuActions = ::pageMenuActions
                        )
                    ),
                    bottomActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.enable_selection),
                            onClick = ::enableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.disable_selection),
                            onClick = ::disableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.check_select_source),
                            onClick = ::checkSource
                        ),
                        AppManagementAction(
                            text = getString(R.string.add_group),
                            onClick = ::selectionAddToGroups
                        ),
                        AppManagementAction(
                            text = getString(R.string.remove_group),
                            onClick = ::selectionRemoveFromGroups
                        ),
                        AppManagementAction(
                            text = getString(R.string.selection_to_top),
                            onClick = ::topSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.selection_to_bottom),
                            onClick = ::bottomSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.export_selection),
                            onClick = ::exportSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.share_selected_source),
                            onClick = ::shareSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.check_selected_interval),
                            onClick = ::checkSelectedInterval
                        ),
                        AppManagementAction(
                            text = getString(R.string.delete),
                            danger = true,
                            onClick = ::delSourceDialog
                        )
                    ),
                    onBack = { finish() },
                    onSelectAll = { selectAll(true) },
                    onInvertSelection = { revertSelection() }
                ) {
                    RssSourceScreen(
                        sources = sourcesState,
                        selectedUrls = selectedUrls.value,
                        isSelectMode = isSelectMode.value,
                        reorderEnabled = searchQueryState.value.isBlank(),
                        onReorder = { reordered -> viewModel.upOrder(reordered) },
                        onToggleSelect = ::toggleSourceSelection,
                        onToggleEnabled = ::toggleSourceEnabled,
                        onEdit = ::editSource,
                        sourceMenuActions = ::sourceMenuActions,
                        debugMessages = debugMessagesState,
                        isChecking = isCheckingState.value,
                        emptyHint = when {
                            sourcesState.isNotEmpty() -> null
                            searchQueryState.value.isBlank() ->
                                getString(R.string.rss_source_empty_import)

                            else ->
                                getString(R.string.rss_source_empty_filter)
                        }
                    )
                }
            }
        }
        container.addView(cv, index)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity<RssSourceEditActivity>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_enabled_group -> {
                updateSearchQuery(getString(R.string.enabled))
            }

            R.id.menu_disabled_group -> {
                updateSearchQuery(getString(R.string.disabled))
            }

            R.id.menu_group_login -> {
                updateSearchQuery(getString(R.string.need_login))
            }

            R.id.menu_group_null -> {
                updateSearchQuery(getString(R.string.no_group))
            }

            R.id.menu_help -> showHelp("SourceMRssHelp")
            else -> if (item.groupId == R.id.source_group) {
                updateSearchQuery("group:${item.title}")
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showFilterMenu() {
        // 与书源管理页一致：当前生效的筛选在菜单里标记出来。
        // 标签加了后缀后不能再用标签文本反推筛选条件，分组走快照索引取原始名。
        val currentMark = "（${getString(R.string.menu_current_option)}）"
        val query = searchQueryState.value
        fun marked(labelRes: Int): String {
            val label = getString(labelRes)
            return if (query == label) label + currentMark else label
        }

        val fixedLabels = listOf(
            getString(R.string.group_manage),
            marked(R.string.enabled),
            marked(R.string.disabled),
            marked(R.string.need_login),
            marked(R.string.no_group)
        )
        // 对话框打开期间分组列表可能被后台 flow 刷新，回调里用的是打开时的快照
        val groupSnapshot = groups.toList()
        val labels = fixedLabels + groupSnapshot.map { group ->
            if (query == "group:$group") group + currentMark else group
        }
        showComposeActionListDialog(
            title = getString(R.string.menu_action_group),
            labels = labels
        ) { index ->
            when (index) {
                0 -> showDialogFragment<GroupManageDialog>()
                1 -> updateSearchQuery(getString(R.string.enabled))
                2 -> updateSearchQuery(getString(R.string.disabled))
                3 -> updateSearchQuery(getString(R.string.need_login))
                4 -> updateSearchQuery(getString(R.string.no_group))
                else -> updateSearchQuery("group:${groupSnapshot[index - fixedLabels.size]}")
            }
        }
    }

    private fun pageMenuActions(): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.import_local)) {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            },
            AppManagementMenuAction(getString(R.string.import_on_line)) {
                showImportDialog()
            },
            AppManagementMenuAction(getString(R.string.import_by_qr_code)) {
                qrCodeResult.launch()
            },
            AppManagementMenuAction(getString(R.string.import_default_rule)) {
                viewModel.importDefault()
            },
            AppManagementMenuAction(getString(R.string.help)) {
                showHelp("SourceMRssHelp")
            }
        )
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.rss_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initGroupFlow() {
        lifecycleScope.launch {
            appDb.rssSourceDao.flowGroups().conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupMenu()
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_selection -> enableSelected()
            R.id.menu_disable_selection -> disableSelected()
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_top_sel -> topSelected()
            R.id.menu_bottom_sel -> bottomSelected()
            R.id.menu_export_selection -> exportSelected()
            R.id.menu_share_source -> shareSelected()
            R.id.menu_check_selected_interval -> checkSelectedInterval()
        }
        return true
    }

    private fun enableSelected() {
        val selection = getSelectedSources()
        updateSelectedEnabled(enabled = true)
        viewModel.enableSelection(selection)
        toastCount(R.string.rss_source_enabled_toast, selection.size)
    }

    private fun disableSelected() {
        val selection = getSelectedSources()
        updateSelectedEnabled(enabled = false)
        viewModel.disableSelection(selection)
        toastCount(R.string.rss_source_disabled_toast, selection.size)
    }

    private fun topSelected() {
        val selection = getSelectedSources()
        viewModel.topSource(*selection.toTypedArray())
        toastCount(R.string.rss_source_top_toast, selection.size)
    }

    private fun bottomSelected() {
        val selection = getSelectedSources()
        viewModel.bottomSource(*selection.toTypedArray())
        toastCount(R.string.rss_source_bottom_toast, selection.size)
    }

    /**
     * 批量操作的即时反馈。
     *
     * 启用/禁用/置顶/置底/改分组之后，行的位置或开关状态要等数据库回流才变，
     * 用户容易以为没点上；给一句明确结果最省事。
     */
    private fun toastCount(resId: Int, count: Int) {
        if (count <= 0) return
        toastOnUi(getString(resId, count))
    }

    /**
     * 校验所选订阅源；没有勾选时校验全部。
     * 校验是页面级任务（离开页面即停），用 Snackbar 提供「取消」入口。
     */
    private fun checkSource() {
        val sources = getSelectedSources().ifEmpty { sourcesState.toList() }
        startCheck(sources)
    }

    private fun startCheck(sources: List<RssSource>) {
        if (sources.isEmpty()) return
        CheckRssSource.stop()
        checkTotal = sources.size
        keepScreenOn(true)
        updateCheckingState(true)
        refreshDebugMessages(force = true)
        checkSnackBar?.dismiss()
        checkSnackBar = Snackbar
            .make(
                binding.root,
                getString(R.string.check_rss_progress, 0, checkTotal),
                Snackbar.LENGTH_INDEFINITE
            )
            .setAction(R.string.cancel) { stopCheck() }
            .apply { show() }
        // 必须先 start 再启动轮询：lifecycleScope 是 Main.immediate，
        // 轮询首循环会同步执行，若此刻校验 job 还没建立，isRunning()==false
        // 会让轮询立即自毁（加载圈被改回、Snackbar 被 dismiss、常亮被清除）
        CheckRssSource.start(lifecycleScope, sources)
        startCheckMessageRefreshJob()
    }

    private fun stopCheck() {
        CheckRssSource.stop()
        updateCheckingState(false)
        refreshDebugMessages(force = true)
        checkSnackBar?.dismiss()
        checkSnackBar = null
        checkMessageRefreshJob?.cancel()
        keepScreenOn(false)
    }

    /** 校验进度轮询：把 Debug 消息缓存同步进 Compose 状态，并更新 Snackbar 进度 */
    private fun startCheckMessageRefreshJob() {
        checkMessageRefreshJob?.cancel()
        checkMessageRefreshJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshDebugMessages()
                    val done = debugMessagesState.values.count { it.contains(finalMessageRegex) }
                    checkSnackBar?.setText(getString(R.string.check_rss_progress, done, checkTotal))
                    if (!CheckRssSource.isRunning()) {
                        updateCheckingState(false)
                        refreshDebugMessages(force = true)
                        checkSnackBar?.dismiss()
                        checkSnackBar = null
                        keepScreenOn(false)
                        checkMessageRefreshJob?.cancel()
                    }
                    delay(500L)
                }
            }
        }
    }

    private fun updateCheckingState(checking: Boolean) {
        if (isCheckingState.value != checking) {
            isCheckingState.value = checking
        }
    }

    private fun keepScreenOn(on: Boolean) {
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshDebugMessages(force: Boolean = false) {
        val next = buildDebugMessagesSnapshot()
        debugMessagesState.keys
            .filter { it !in next }
            .toList()
            .forEach { debugMessagesState.remove(it) }
        next.forEach { (url, message) ->
            if (force || debugMessagesState[url] != message) {
                debugMessagesState[url] = message
            }
        }
    }

    /** 与书源管理页同语义：校验停了但还没写出终态的源，补记为失败 */
    private fun buildDebugMessagesSnapshot(): Map<String, String> {
        return sourcesState.mapNotNull { source ->
            val initial = Debug.debugMessageMap[source.sourceUrl].orEmpty()
            if (initial.isBlank()) {
                null
            } else {
                if (!CheckRssSource.isRunning() && !initial.contains(finalMessageRegex)) {
                    Debug.updateFinalMessage(source.sourceUrl, "校验失败")
                }
                source.sourceUrl to Debug.debugMessageMap[source.sourceUrl].orEmpty()
            }
        }.toMap()
    }

    private fun exportSelected() {
        val selection = getSelectedSources()
        viewModel.saveToFile(selection) { file, name ->
            exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    name, file, "application/json"
                )
            }
        }
    }

    private fun shareSelected() {
        val selection = getSelectedSources()
        viewModel.saveToFile(selection) { file, _ ->
            share(file)
        }
    }

    private fun selectionAddToGroups() {
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.add_group),
            hint = getString(R.string.group_name),
            suggestions = groups.toList(),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    val selection = getSelectedSources()
                    viewModel.selectionAddToGroups(selection, text)
                    toastCount(R.string.rss_source_group_added_toast, selection.size)
                }
            }
        )
    }

    private fun selectionRemoveFromGroups() {
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.remove_group),
            hint = getString(R.string.group_name),
            suggestions = groups.toList(),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    val selection = getSelectedSources()
                    viewModel.selectionRemoveFromGroups(selection, text)
                    toastCount(R.string.rss_source_group_removed_toast, selection.size)
                }
            }
        )
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedUrls.value = sourcesState.map { it.sourceUrl }.toSet()
        } else {
            selectedUrls.value = emptySet()
        }
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun revertSelection() {
        val allUrls = sourcesState.map { it.sourceUrl }.toSet()
        selectedUrls.value = allUrls - selectedUrls.value
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun onClickSelectBarMainAction() {
        delSourceDialog()
    }

    private fun delSourceDialog() {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                val selection = getSelectedSources()
                viewModel.delWithUndo(selection) { deleted ->
                    runOnUiThread {
                        if (deleted.isNotEmpty()) {
                            binding.root.undoSnackbar(
                                getString(R.string.deleted_rss_sources, deleted.size),
                                getString(R.string.undo)
                            ) {
                                viewModel.restoreDeleted(deleted)
                            }
                        }
                    }
                }
                selectedUrls.value = emptySet()
                isSelectMode.value = false
            }
        )
    }

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.source_group)
        groups.forEach {
            menu.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun upSourceFlow(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            // STARTED 门控：页面不可见时不 collect，避免后台持续Room查询+列表刷新
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                when {
                searchKey.isNullOrBlank() -> {
                    appDb.rssSourceDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.rssSourceDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.rssSourceDao.flowDisabled()
                }

                searchKey == getString(R.string.need_login) -> {
                    appDb.rssSourceDao.flowLogin()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.rssSourceDao.flowNoGroup()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.rssSourceDao.flowGroupSearch(key)
                }

                else -> {
                    appDb.rssSourceDao.flowSearch(searchKey)
                }
            }.catch {
                AppLog.put("订阅源管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                sourcesState.replaceByIndex(it, ::sameRssSourceListContent)
                val currentUrls = it.mapTo(mutableSetOf()) { source -> source.sourceUrl }
                selectedUrls.value = selectedUrls.value.filter { url -> url in currentUrls }.toSet()
                isSelectMode.value = selectedUrls.value.isNotEmpty()
                upCountView()
                delay(100)
                }
            }
        }
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        // 与书源管理页一致：输入框下方带历史导入地址，可一键填入/删除
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "url",
            suggestions = cacheUrls,
            deletable = true,
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotBlank()) {
                    if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                        cacheUrls.add(0, text)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportRssSourceDialog(text))
                }
            },
            onSuggestionDeleted = { url ->
                cacheUrls.remove(url)
                aCache.put(importRecordKey, cacheUrls.joinToString(","))
            }
        )
    }

    private fun toggleSourceSelection(source: RssSource) {
        val current = selectedUrls.value.toMutableSet()
        if (source.sourceUrl in current) {
            current.remove(source.sourceUrl)
        } else {
            current.add(source.sourceUrl)
        }
        selectedUrls.value = current
        isSelectMode.value = current.isNotEmpty()
        upCountView()
    }

    private fun toggleSourceEnabled(source: RssSource, enabled: Boolean) {
        val updated = source.copy(enabled = enabled)
        val index = sourcesState.indexOfFirst { it.sourceUrl == source.sourceUrl }
        if (index >= 0) {
            sourcesState[index] = updated
        }
        viewModel.update(updated)
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val urls = selectedUrls.value
        if (urls.isEmpty()) return
        sourcesState.indices.forEach { index ->
            val source = sourcesState[index]
            if (source.sourceUrl in urls && source.enabled != enabled) {
                sourcesState[index] = source.copy(enabled = enabled)
            }
        }
    }

    private fun sameRssSourceListContent(old: RssSource, new: RssSource): Boolean {
        return old.sourceUrl == new.sourceUrl &&
            old.sourceName == new.sourceName &&
            old.sourceIcon == new.sourceIcon &&
            old.sourceGroup == new.sourceGroup &&
            old.sourceComment == new.sourceComment &&
            old.enabled == new.enabled &&
            old.loginUrl == new.loginUrl &&
            old.lastUpdateTime == new.lastUpdateTime &&
            old.customOrder == new.customOrder &&
            old.type == new.type &&
            old.preload == new.preload &&
            old.cacheFirst == new.cacheFirst
    }

    private fun updateSearchQuery(query: String) {
        if (searchQueryState.value == query) {
            return
        }
        searchQueryState.value = query
        upSourceFlow(query)
    }

    private fun editSource(source: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", source.sourceUrl)
        }
    }

    private fun sourceMenuActions(source: RssSource): List<AppManagementMenuAction> {
        return buildList {
            add(
                AppManagementMenuAction(getString(R.string.view_rss_source)) {
                    // 直接进入该源的目录/文章页，省去先回订阅 tab 再找源的绕路
                    RssSortActivity.start(this@RssSourceActivity, null, source.sourceUrl)
                }
            )
            add(
                AppManagementMenuAction(getString(R.string.selection_to_top)) {
                    viewModel.topSource(source)
                }
            )
            add(
                AppManagementMenuAction(getString(R.string.selection_to_bottom)) {
                    viewModel.bottomSource(source)
                }
            )
            add(
                AppManagementMenuAction(getString(R.string.check_rss_source)) {
                    startCheck(listOf(source))
                }
            )
            // 与编辑页的登录入口同一规则：配置了登录地址才出现
            if (!source.loginUrl.isNullOrBlank()) {
                add(
                    AppManagementMenuAction(getString(R.string.login)) {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "rssSource")
                            putExtra("key", source.sourceUrl)
                        }
                    }
                )
            }
            add(
            AppManagementMenuAction(
                text = getString(R.string.delete),
                danger = true
            ) {
                showComposeConfirmDialog(
                    title = getString(R.string.draw),
                    message = getString(R.string.sure_del) + "\n" + source.sourceName,
                    positiveText = getString(R.string.yes),
                    negativeText = getString(R.string.no),
                    dangerPositive = true,
                    onPositive = {
                        viewModel.delWithUndo(listOf(source)) { deleted ->
                            runOnUiThread {
                                if (deleted.isNotEmpty()) {
                                    binding.root.undoSnackbar(
                                        getString(R.string.deleted_rss_sources, deleted.size),
                                        getString(R.string.undo)
                                    ) {
                                        viewModel.restoreDeleted(deleted)
                                    }
                                }
                            }
                        }
                    }
                )
            }
            )
        }
    }

    private fun getSelectedSources(): List<RssSource> {
        val urls = selectedUrls.value
        return sourcesState.filter { it.sourceUrl in urls }
    }

    private fun checkSelectedInterval() {
        val urls = selectedUrls.value
        val positions = mutableListOf<Int>()
        sourcesState.forEachIndexed { index, source ->
            if (source.sourceUrl in urls) {
                positions.add(index)
            }
        }
        if (positions.isEmpty()) return
        val minPos = positions.min()
        val maxPos = positions.max()
        val newSelected = urls.toMutableSet()
        for (i in minPos..maxPos) {
            newSelected.add(sourcesState[i].sourceUrl)
        }
        selectedUrls.value = newSelected
        upCountView()
    }

    private fun upCountView() {
        binding.selectActionBar.upCountView(
            getSelectedSources().size,
            sourcesState.size
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // 校验挂在页面生命周期上：离开页面即停止，不留僵尸任务
        if (CheckRssSource.isRunning()) {
            CheckRssSource.stop()
        }
    }

}
