package io.legado.app.ui.rss.source.edit

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.data.entities.RssSource
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.host.setV5Content
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.debug.RssSourceDebugActivity
import io.legado.app.ui.widget.dialog.UrlOptionDialog
import io.legado.app.ui.widget.dialog.VariableDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.ui.widget.text.EditEntity
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.share
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.bottomPadding
import kotlin.text.isNotEmpty

class RssSourceEditActivity :
    AppCompatActivity(),
    KeyboardToolPop.CallBack,
    VariableDialog.Callback {

    private val viewModel by viewModels<RssSourceEditViewModel>()
    private val adapter by lazy { RssSourceEditAdapter() }
    private val sourceEntities: ArrayList<EditEntity> = ArrayList()
    private val listEntities: ArrayList<EditEntity> = ArrayList()
    private val webViewEntities: ArrayList<EditEntity> = ArrayList()
    private val startEntities: ArrayList<EditEntity> = ArrayList()

    // 顶部配置状态（原 binding 上的 checkbox/spinner 状态字段化）
    private var enabled by mutableStateOf(true)
    private var singleUrl by mutableStateOf(false)
    private var enabledCookieJar by mutableStateOf(false)
    private var preload by mutableStateOf(false)
    private var typeIndex by mutableIntStateOf(0)
    private var articleStyleIndex by mutableIntStateOf(0)

    // 当前选中的 tab
    private var selectedTab by mutableIntStateOf(0)

    // AndroidView 创建的 RecyclerView 引用（原 binding.recyclerView）
    private var recyclerView: RecyclerView? = null

    private val rssTypeOptions by lazy { resources.getStringArray(R.array.rss_type).toList() }
    private val layoutTypeOptions by lazy { resources.getStringArray(R.array.layout_type).toList() }

    private val selectDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                sendText(uri.toString())
            } else {
                sendText(uri.path.toString())
            }
        }
    }
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it?.let {
            viewModel.importSource(it) { source: RssSource ->
                upSourceView(source)
            }
        }
    }

    private lateinit var softKeyboardTool: KeyboardToolPop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content {
            RssSourceEditScreen(
                title = getString(R.string.rss_source_edit),
                rssTypeOptions = rssTypeOptions,
                layoutTypeOptions = layoutTypeOptions,
                enabled = enabled,
                singleUrl = singleUrl,
                enabledCookieJar = enabledCookieJar,
                preload = preload,
                typeIndex = typeIndex,
                articleStyleIndex = articleStyleIndex,
                selectedTab = selectedTab,
                onBack = { finish() },
                onEnabledChange = { enabled = it },
                onSingleUrlChange = { singleUrl = it },
                onEnabledCookieJarChange = { enabledCookieJar = it },
                onPreloadChange = { preload = it },
                onTypeChange = { typeIndex = it },
                onArticleStyleChange = { articleStyleIndex = it },
                onTabSelected = ::onTabSelected,
                onSave = ::save,
                buildMenuActions = ::buildMenuActions,
                recyclerViewFactory = ::createRecyclerView,
            )
        }
        // softKeyboardTool 依赖 setContentView 后的 content 根，须在 setV5Content 之后初始化
        softKeyboardTool = KeyboardToolPop(
            this,
            lifecycleScope,
            findViewById<ViewGroup>(android.R.id.content),
            this
        )
        softKeyboardTool.attachToWindow(window)
        viewModel.initData(intent) {
            upSourceView(viewModel.rssSource)
        }
    }

    private fun onTabSelected(index: Int) {
        selectedTab = index
        setEditEntities(index)
    }

    private fun createRecyclerView(context: Context): RecyclerView {
        return RecyclerView(context).apply {
            setEdgeEffectColor(context.primaryColor)
            val createSpanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int = when (this@RssSourceEditActivity.adapter.getItemViewType(position)) {
                    EditEntity.ViewType.checkBox -> 1
                    else -> 2
                }
            }
            if (this@RssSourceEditActivity.adapter.editEntityMaxLine < 999) {
                layoutManager = object : GridLayoutManager(context, 2) {
                    init {
                        spanSizeLookup = createSpanSizeLookup
                    }
                    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean) = false
                    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean) = false
                }
            } else {
                layoutManager = GridLayoutManager(context, 2).apply {
                    spanSizeLookup = createSpanSizeLookup
                }
            }
            adapter = this@RssSourceEditActivity.adapter
            val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus is EditText) {
                    newFocus.postDelayed({
                        if (!isDestroyed && !isFinishing) {
                            sendText("")
                        }
                    }, 120)
                }
            }
            globalFocusChangeListener = focusListener
            viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            setOnApplyWindowInsetsListenerCompat { view, windowInsets ->
                val navigationBarHeight = windowInsets.navigationBarHeight
                val imeHeight = windowInsets.imeHeight
                view.bottomPadding = if (imeHeight == 0) navigationBarHeight else 0
                softKeyboardTool.initialPadding = imeHeight
                windowInsets
            }
            this@RssSourceEditActivity.recyclerView = this
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!LocalConfig.ruleHelpVersionIsLast) {
            showHelp("rssRuleHelp")
        }
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val view = window.decorView.findFocus()
            if (view is EditText) {
                result.data?.getStringExtra("text")?.let {
                    view.setText(it)
                }
                result.data?.getIntExtra("cursorPosition", -1)?.takeIf { it in 0 ..< view.text.length }?.let {
                    view.setSelection(it)
                }
            } else {
                toastOnUi(R.string.focus_lost_on_textbox)
            }
        }
    }

    private fun onFullEditClicked() {
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val hint = findParentTextInputLayout(view)?.hint?.toString()
            val currentText = view.text.toString()
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("title", hint)
                putExtra("cursorPosition", view.selectionStart)
            }
            textEditLauncher.launch(intent)
        } else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    private fun findParentTextInputLayout(view: View): TextInputLayout? {
        var parent = view.parent
        while (parent != null && parent !is TextInputLayout) {
            parent = parent.parent
        }
        return parent
    }

    private fun save() {
        viewModel.save(getRssSource()) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun debugSource() {
        viewModel.save(getRssSource()) { source ->
            startActivity<RssSourceDebugActivity> {
                putExtra("key", source.sourceUrl)
            }
        }
    }

    private fun login() {
        viewModel.save(getRssSource()) { source ->
            startActivity<SourceLoginActivity> {
                putExtra("type", "rssSource")
                putExtra("key", source.sourceUrl)
            }
        }
    }

    /**
     * 构建溢出菜单动作。每次点开菜单时重新构建，保证「登录」可见性与「自动补全」勾选态最新。
     */
    private fun buildMenuActions(): List<Pair<String, () -> Unit>> {
        val list = ArrayList<Pair<String, () -> Unit>>()
        list.add(getString(R.string.edit_content) to ::onFullEditClicked)
        list.add(getString(R.string.debug_source) to ::debugSource)
        if (!getRssSource().loginUrl.isNullOrBlank()) {
            list.add(getString(R.string.login) to ::login)
        }
        list.add(getString(R.string.set_source_variable) to ::setSourceVariable)
        list.add(getString(R.string.cookie) to {
            viewModel.clearCookie(getRssSource().sourceUrl)
        })
        list.add(
            (getString(R.string.auto_complete) + if (viewModel.autoComplete) " ✓" else "") to {
                viewModel.autoComplete = !viewModel.autoComplete
            }
        )
        list.add(getString(R.string.copy_source) to { sendToClip(GSON.toJson(getRssSource())) })
        list.add(getString(R.string.paste_source) to { viewModel.pasteSource { upSourceView(it) } })
        list.add(getString(R.string.import_by_qr_code) to { qrCodeResult.launch() })
        list.add(getString(R.string.str_share) to { share(GSON.toJson(getRssSource())) })
        list.add(getString(R.string.qr_share) to {
            shareWithQr(
                GSON.toJson(getRssSource()),
                getString(R.string.share_rss_source),
                ErrorCorrectionLevel.L
            )
        })
        list.add(getString(R.string.log) to { showDialogFragment<AppLogDialog>() })
        list.add(getString(R.string.help) to { showHelp("rssRuleHelp") })
        return list
    }

    override fun finish() {
        val source = getRssSource()
        if (!source.equal(viewModel.rssSource ?: RssSource())) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.finish()
                }
            }
        } else {
            super.finish()
        }
    }

    private var globalFocusChangeListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    override fun onDestroy() {
        globalFocusChangeListener?.let {
            recyclerView?.viewTreeObserver?.removeOnGlobalFocusChangeListener(it)
        }
        globalFocusChangeListener = null
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun setEditEntities(tabPosition: Int?) {
        adapter.editEntities = when (tabPosition) {
            1 -> startEntities
            2 -> listEntities
            3 -> webViewEntities
            else -> sourceEntities
        }
        recyclerView?.scrollToPosition(0)
        window.decorView.rootView.clearFocus()
    }

    private fun upSourceView(rssSource: RssSource?) {
        val rs = rssSource ?: RssSource()
        enabled = rs.enabled
        singleUrl = rs.singleUrl
        enabledCookieJar = rs.enabledCookieJar == true
        preload = rs.preload
        typeIndex = if (rs.type in 0..<rssTypeOptions.size) rs.type else 0
        articleStyleIndex = if (rs.articleStyle in 0..<layoutTypeOptions.size) rs.articleStyle else 0
        sourceEntities.clear()
        sourceEntities.apply {
            add(EditEntity("sourceName", rs.sourceName, R.string.source_name))
            add(EditEntity("sourceUrl", rs.sourceUrl, R.string.source_url))
            add(EditEntity("sourceIcon", rs.sourceIcon, R.string.source_icon))
            add(EditEntity("sourceGroup", rs.sourceGroup, R.string.source_group))
            add(EditEntity("sourceComment", rs.sourceComment, R.string.comment))
            add(EditEntity("searchUrl", rs.searchUrl, R.string.r_search_url))
            add(EditEntity("sortUrl", rs.sortUrl, R.string.sort_url))
            add(EditEntity("loginUrl", rs.loginUrl, R.string.login_url))
            add(EditEntity("loginUi", rs.loginUi, R.string.login_ui))
            add(EditEntity("loginCheckJs", rs.loginCheckJs, R.string.login_check_js))
            add(EditEntity("coverDecodeJs", rs.coverDecodeJs, R.string.cover_decode_js))
            add(EditEntity("header", rs.header, R.string.source_http_header))
            add(EditEntity("variableComment", rs.variableComment, R.string.variable_comment))
            add(EditEntity("concurrentRate", rs.concurrentRate, R.string.concurrent_rate))
            add(EditEntity("jsLib", rs.jsLib, "jsLib"))
        }
        startEntities.clear()
        startEntities.apply {
            add(EditEntity("startHtml", rs.startHtml, R.string.r_startHtml))
            add(EditEntity("startStyle", rs.startStyle, R.string.r_startStyle))
            add(EditEntity("startJs", rs.startJs, R.string.r_startJs))
            add(EditEntity("preloadJs", rs.preloadJs, R.string.r_preloadJs))
        }
        listEntities.clear()
        listEntities.apply {
            add(EditEntity("ruleArticles", rs.ruleArticles, R.string.r_articles))
            add(EditEntity("ruleNextPage", rs.ruleNextPage, R.string.r_next))
            add(EditEntity("ruleTitle", rs.ruleTitle, R.string.r_title))
            add(EditEntity("rulePubDate", rs.rulePubDate, R.string.r_date))
            add(EditEntity("ruleDescription", rs.ruleDescription, R.string.r_description))
            add(EditEntity("ruleImage", rs.ruleImage, R.string.r_image))
            add(EditEntity("ruleLink", rs.ruleLink, R.string.r_link))
        }
        webViewEntities.clear()
        webViewEntities.apply {
            add(
                EditEntity(
                    "enableJs",
                    rs.enableJs.toString(),
                    R.string.enable_js,
                    EditEntity.ViewType.checkBox
                )
            )
            add(
                EditEntity(
                    "loadWithBaseUrl",
                    rs.loadWithBaseUrl.toString(),
                    R.string.load_with_base_url,
                    EditEntity.ViewType.checkBox
                )
            )
            add(
                EditEntity(
                    "showWebLog",
                    rs.showWebLog.toString(),
                    R.string.load_with_web_log,
                    EditEntity.ViewType.checkBox
                )
            )
            add(
                EditEntity(
                    "cacheFirst",
                    rs.cacheFirst.toString(),
                    R.string.cache_first,
                    EditEntity.ViewType.checkBox
                )
            )
            add(EditEntity("ruleContent", rs.ruleContent, R.string.r_content))
            add(EditEntity("style", rs.style, R.string.r_style))
            add(EditEntity("injectJs", rs.injectJs, R.string.r_inject_js))
            add(EditEntity("contentWhitelist", rs.contentWhitelist, R.string.c_whitelist))
            add(EditEntity("contentBlacklist", rs.contentBlacklist, R.string.c_blacklist))
            add(
                EditEntity(
                    "shouldOverrideUrlLoading",
                    rs.shouldOverrideUrlLoading,
                    "url跳转拦截(js, 返回true拦截,js变量url,可以通过js打开url,比如调用阅读搜索,添加书架等,简化规则写法,不用webView js注入)"
                )
            )
        }
        selectedTab = 0
        setEditEntities(0)
    }

    private fun getRssSource(): RssSource {
        val source = viewModel.rssSource?.copy() ?: RssSource()
        source.enabled = enabled
        source.singleUrl = singleUrl
        source.enabledCookieJar = enabledCookieJar
        source.preload = preload
        source.type = typeIndex
        source.articleStyle = articleStyleIndex
        sourceEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "sourceName" -> source.sourceName = it.value ?: ""
                "sourceUrl" -> source.sourceUrl = it.value ?: ""
                "sourceIcon" -> source.sourceIcon = it.value ?: ""
                "sourceGroup" -> source.sourceGroup = it.value
                "sourceComment" -> source.sourceComment = it.value
                "loginUrl" -> source.loginUrl = it.value
                "loginUi" -> source.loginUi = it.value
                "loginCheckJs" -> source.loginCheckJs = it.value
                "coverDecodeJs" -> source.coverDecodeJs = it.value
                "header" -> source.header = it.value
                "variableComment" -> source.variableComment = it.value
                "concurrentRate" -> source.concurrentRate = it.value
                "searchUrl" -> source.searchUrl = it.value
                "sortUrl" -> source.sortUrl = it.value
                "jsLib" -> source.jsLib = it.value
            }
        }
        startEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "startHtml" -> source.startHtml = it.value
                "startStyle" -> source.startStyle = it.value
                "startJs" -> source.startJs = it.value
                "preloadJs" -> source.preloadJs = it.value
            }
        }
        listEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "ruleArticles" -> source.ruleArticles = it.value
                "ruleNextPage" -> source.ruleNextPage =
                    viewModel.ruleComplete(it.value, source.ruleArticles, 2)

                "ruleTitle" -> source.ruleTitle =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "rulePubDate" -> source.rulePubDate =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "ruleDescription" -> source.ruleDescription =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "ruleImage" -> source.ruleImage =
                    viewModel.ruleComplete(it.value, source.ruleArticles, 3)

                "ruleLink" -> source.ruleLink =
                    viewModel.ruleComplete(it.value, source.ruleArticles)
            }
        }
        webViewEntities.forEach {
            it.value = it.value?.takeIf { s -> s.isNotBlank() }
            when (it.key) {
                "enableJs" -> source.enableJs = it.value.isTrue()
                "loadWithBaseUrl" -> source.loadWithBaseUrl = it.value.isTrue()
                "showWebLog" -> source.showWebLog = it.value.isTrue()
                "cacheFirst" -> source.cacheFirst = it.value.isTrue()
                "ruleContent" -> source.ruleContent =
                    viewModel.ruleComplete(it.value, source.ruleArticles)

                "style" -> source.style = it.value
                "injectJs" -> source.injectJs = it.value
                "contentWhitelist" -> source.contentWhitelist = it.value
                "contentBlacklist" -> source.contentBlacklist = it.value
                "shouldOverrideUrlLoading" -> source.shouldOverrideUrlLoading = it.value
            }
        }
        return source
    }

    private fun setSourceVariable() {
        viewModel.save(getRssSource()) { source ->
            lifecycleScope.launch {
                val comment =
                    source.getDisplayVariableComment("源变量可在js中通过source.getVariable()获取")
                val variable = withContext(Dispatchers.IO) { source.getVariable() }
                showDialogFragment(
                    VariableDialog(
                        getString(R.string.set_source_variable),
                        source.getKey(),
                        variable,
                        comment
                    )
                )
            }
        }
    }

    override fun setVariable(key: String, variable: String?) {
        viewModel.rssSource?.setVariable(variable)
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("插入URL参数", "urlOption"),
            SelectItem("订阅源教程", "ruleHelp"),
            SelectItem("js教程", "jsHelp"),
            SelectItem("正则教程", "regexHelp"),
            SelectItem("选择文件", "selectFile"),
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "urlOption" -> UrlOptionDialog(this) {
                sendText(it)
            }.show()

            "ruleHelp" -> showHelp("rssRuleHelp")
            "jsHelp" -> showHelp("jsHelp")
            "regexHelp" -> showHelp("regexHelp")
            "selectFile" -> selectDoc.launch {
                mode = HandleFileContract.FILE
            }
        }
    }

    override fun sendText(text: String) {
        val view = window.decorView.findFocus()
        if (view is EditText) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            if (text.isNotEmpty()) {
                val edit = view.editableText//获取EditText的文字
                if (start < 0 || start >= edit.length) {
                    edit.append(text)
                } else {
                    edit.replace(start, end, text)//光标所在位置插入文字
                }
            }
            if (adapter.editEntityMaxLine >= 999) {
                view.post {
                    val rv = recyclerView ?: return@post
                    val editTextLocation = IntArray(2)
                    view.getLocationOnScreen(editTextLocation)
                    val recyclerViewLocation = IntArray(2)
                    rv.getLocationOnScreen(recyclerViewLocation)
                    val layout = view.layout
                    if (layout != null) {
                        val line = layout.getLineForOffset(end)
                        val cursorYInEditText = layout.getLineTop(line)
                        // 光标相对于屏幕的位置
                        val cursorYOnScreen = editTextLocation[1] + cursorYInEditText
                        // 光标相对于RecyclerView的位置
                        val cursorYInRecyclerView = cursorYOnScreen - recyclerViewLocation[1]
                        val recyclerViewBottom = rv.height - 120 //考虑键盘的经验值
                        // 如果光标不在可见范围内，则滚动到光标位置
                        if (cursorYInRecyclerView !in 0..recyclerViewBottom) {
                            val scrollDistance = cursorYInRecyclerView - recyclerViewBottom / 3
                            if (scrollDistance > 0 && rv.canScrollVertically(1) || scrollDistance < 0 && rv.canScrollVertically(-1)) {
                                rv.smoothScrollBy(0, scrollDistance)
                            }
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.undo)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.redo)
        }
    }

}
