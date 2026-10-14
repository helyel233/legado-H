package io.legado.app.ui.host

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.http.CookieStore
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.login.SourceLoginViewModel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 源登录（V5）。业务逐行搬自旧 SourceLoginActivity + SourceLoginDialog + WebViewLoginFragment。
 *
 * 两条路径（与旧分发器同口径）：
 * ① 源未定义 loginUi → WebView 打开登录页，抓取 Cookie（原 WebViewLoginFragment）；
 * ② 源定义了 loginUi → 按 RowUi 渲染动态表单（原 SourceLoginDialog，822 行）。
 *
 * 关键行为保留：
 * - 点「保存」先置 oKToClose=true，即使 login() 失败也不再兜底保存；
 * - loginData 为空时直接 removeLoginInfo + 关闭，不执行 login() JS；
 * - 退出时仅当 !oKToClose && hasChange 才兜底持久化；
 * - 配置变更（转屏）不关闭宿主（旧代码有 isChangingConfigurations 守卫，这里靠 ViewModel 存活 + 不重复 finish）。
 */
class HostSourceLoginActivity : AppCompatActivity(), SourceLoginJsExtensions.Callback {

    internal val viewModel by viewModels<SourceLoginViewModel>()

    private var loginUrl: String? = null
    private var rowUis: List<RowUi>? = null
    internal var hasChange by mutableStateOf(false)
    private var oKToClose = false
    internal var titleText by mutableStateOf("")
    private var menuOpen by mutableStateOf(false)
    /** null=未定；true=WebView 登录；false=自定义表单 */
    internal var webLogin: Boolean? by mutableStateOf(null)

    /** 表单项状态（替代旧 flexbox 子 View + rowUiName + id=index+1000） */
    internal val rows = mutableStateListOf<RowState>()

    // WebView 登录支路
    private var pooledWebView: PooledWebView? = null
    private var checking = false

    private val initHandler by lazy { buildMainHandler() }

    private val sourceLoginJsExtensions by lazy {
        SourceLoginJsExtensions(this, viewModel.source, viewModel.bookType, this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initData(
            intent,
            success = { source -> initView(source) },
            error = { finish() },
        )
        setV5Content { HostSourceLoginScreen(this) }
    }

    private fun initView(source: BaseSource) {
        titleText = getString(R.string.login_source, source.getTag())
        loginUrl = source.getLoginJs()
        val loginUiStr = source.loginUi
        if (loginUiStr.isNullOrEmpty()) {
            webLogin = true
            return
        }
        webLogin = false
        val codeStr = loginUiStr.let {
            when {
                it.startsWith("@js:") -> it.substring(4)
                it.startsWith("<js>") -> it.substring(4, it.lastIndexOf("<"))
                else -> null
            }
        }
        lifecycleScope.launch(Main) {
            val list = withContext(IO) {
                val json = if (codeStr != null) evalUiJs(codeStr) else loginUiStr
                loginUi(json)
            }
            rowUis = list
            rebuildRows(list, delta = false)
        }
    }

    // ---------- JS 求值 / 解析 ----------

    private suspend fun evalUiJs(jsStr: String): String? {
        val source = viewModel.source ?: return null
        val loginJS = loginUrl ?: ""
        val result = rowUis?.let { getLoginData() } ?: viewModel.loginInfo.toMutableMap()
        return try {
            runScriptWithContext {
                source.evalJS("$loginJS\n$jsStr") {
                    put("result", result)
                    put("book", viewModel.book)
                    put("chapter", viewModel.chapter)
                }.toString()
            }
        } catch (e: Exception) {
            AppLog.put(source.getTag() + " loginUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    private fun loginUi(json: String?): List<RowUi>? {
        return GSON.fromJsonArray<RowUi>(json).onFailure {
            AppLog.put("loginUi json parse err:" + it.localizedMessage, it)
        }.getOrNull()
    }

    // ---------- 表单状态构建 ----------

    private fun rebuildRows(list: List<RowUi>?, delta: Boolean) {
        val loginInfo = viewModel.loginInfo
        if (delta) {
            // 增量：保留同名项已输入的值与标签
            val old = rows.associateBy { it.name }
            val newRows = list.orEmpty().map { rowUi ->
                val chars = rowUi.chars?.filterNotNull().orEmpty()
                val kept = old[rowUi.name]
                RowState(
                    name = rowUi.name,
                    type = rowUi.type,
                    action = rowUi.action,
                    chars = chars,
                    default = rowUi.default,
                    justify = rowUi.style?.layout_justifySelf,
                ).apply {
                    if (kept != null) {
                        value = kept.value
                        label = kept.label
                        selectedIndex = kept.selectedIndex
                    } else {
                        value = loginInfo[rowUi.name] ?: rowUi.default
                        ?: chars.firstOrNull() ?: ""
                        label = rowUi.name
                        selectedIndex = chars.indexOf(rowUi.default).coerceAtLeast(0)
                    }
                }
            }
            rows.clear()
            rows.addAll(newRows)
        } else {
            rows.clear()
            list.orEmpty().forEach { rowUi ->
                val chars = rowUi.chars?.filterNotNull().orEmpty()
                val initValue = loginInfo[rowUi.name] ?: rowUi.default
                ?: chars.firstOrNull() ?: ""
                val state = RowState(
                    name = rowUi.name,
                    type = rowUi.type,
                    action = rowUi.action,
                    chars = chars,
                    default = rowUi.default,
                    justify = rowUi.style?.layout_justifySelf,
                ).apply {
                    value = initValue
                    label = rowUi.name
                    selectedIndex = chars.indexOf(initValue).coerceAtLeast(0)
                }
                rows.add(state)
            }
        }
        // viewName 三段解析：null→name；'字面量'→去引号；否则 IO 执行 JS
        rows.forEach { rowUiState ->
            val rowUi = list?.firstOrNull { it.name == rowUiState.name } ?: return@forEach
            resolveLabel(rowUiState, rowUi)
        }
    }

    private fun resolveLabel(state: RowState, rowUi: RowUi) {
        val viewName = rowUi.viewName
        when {
            viewName == null -> state.label = rowUi.name
            viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'' ->
                state.label = viewName.substring(1, viewName.length - 1)

            else -> lifecycleScope.launch {
                val n = withContext(IO) { evalUiJs(viewName) }
                state.label = if (n.isNullOrEmpty()) "null" else n
            }
        }
    }

    // ---------- 取值 / 提交 ----------

    private fun getLoginData(): MutableMap<String, String> {
        val loginData = hashMapOf<String, String>()
        rows.forEach { row ->
            if (row.type == RowUi.Type.text || row.type == RowUi.Type.password) {
                // 没文本时存空字符串，而不是删除 loginInfo（与旧实现一致）
                loginData[row.name] = row.value.ifEmpty { row.default ?: "" }
            }
        }
        return viewModel.loginInfo.toMutableMap().apply { putAll(loginData) }
    }

    internal fun handleButtonClick(action: String?, name: String, isLongClick: Boolean) {
        val source = viewModel.source ?: return
        val loginData = getLoginData()
        lifecycleScope.launch(IO) {
            if (action.isAbsUrl()) {
                openUrl(action!!)
            } else if (action != null) {
                val loginJS = loginUrl ?: return@launch
                kotlin.runCatching {
                    runScriptWithContext {
                        source.evalJS("$loginJS\n$action") {
                            put("java", sourceLoginJsExtensions)
                            put("result", loginData)
                            put("book", viewModel.book)
                            put("chapter", viewModel.chapter)
                            put("isLongClick", isLongClick)
                        }
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("LoginUI Button $name JavaScript error", e)
                }
            }
        }
    }

    /** 对应旧 toolbar 的 menu_ok */
    internal fun submitLogin() {
        val source = viewModel.source ?: return
        oKToClose = true
        lifecycleScope.launch(IO) {
            val loginData = getLoginData()
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) { finish() }
                return@launch
            }
            if (!source.putLoginInfo(GSON.toJson(loginData))) return@launch
            try {
                val loginJS = loginUrl ?: return@launch
                val js = "if (typeof login=='function'){ login.apply(this); } else { throw('Function login not implements!!!') }"
                runScriptWithContext {
                    source.evalJS("$loginJS\n$js") {
                        put("java", sourceLoginJsExtensions)
                        put("result", loginData)
                        put("book", viewModel.book)
                        put("chapter", viewModel.chapter)
                        put("isLongClick", false)
                    }
                }
                toastOnUi(R.string.success)
                withContext(Main) { finish() }
            } catch (e: Exception) {
                AppLog.put("登录出错\n${e.localizedMessage}", e)
                toastOnUi("登录出错\n${e.localizedMessage}")
                e.printOnDebug()
            }
        }
    }

    // ---------- JS 反向回调 ----------

    override fun upUiData(data: Map<String, Any?>?) {
        runOnUiThread {
            hasChange = true
            handleUpUiData(data)
        }
    }

    override fun reUiView(deltaUp: Boolean) {
        runOnUiThread {
            if (deltaUp) hasChange = true
            handleReUiView(deltaUp)
        }
    }

    private fun handleReUiView(deltaUp: Boolean) {
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return
        val codeStr = loginUiStr.let {
            when {
                it.startsWith("@js:") -> it.substring(4)
                it.startsWith("<js>") -> it.substring(4, it.lastIndexOf("<"))
                else -> null
            }
        }
        lifecycleScope.launch(Main) {
            val list = withContext(IO) {
                val json = if (codeStr != null) evalUiJs(codeStr) else loginUiStr
                loginUi(json)
            }
            rowUis = list
            rebuildRows(list, delta = deltaUp)
        }
    }

    private fun handleUpUiData(data: Map<String, Any?>?) {
        if (data == null) {
            // 全量重置为 default
            val newLoginInfo = mutableMapOf<String, String>()
            rows.forEach { row ->
                val value = row.default ?: ""
                if (row.type == RowUi.Type.text || row.type == RowUi.Type.password) {
                    newLoginInfo[row.name] = value
                    row.value = value
                } else if (row.type == RowUi.Type.toggle) {
                    val char = row.default ?: row.chars.firstOrNull() ?: ""
                    newLoginInfo[row.name] = char
                    row.value = char
                } else {
                    newLoginInfo[row.name] = value
                }
            }
            viewModel.loginInfo = newLoginInfo
            return
        }
        val loginInfo = viewModel.loginInfo
        data.forEach { (key, value) ->
            val row = rows.firstOrNull { it.name == key }
            val str = value?.toString() ?: ""
            if (row != null) {
                row.value = str
                row.selectedIndex = row.chars.indexOf(str).coerceAtLeast(0)
            }
            loginInfo[key] = str
        }
    }

    // ---------- 菜单动作（旧 source_login.xml 三项） ----------

    internal fun buildMenuActions(): List<Pair<String, () -> Unit>> {
        val source = viewModel.source
        return listOf(
            getString(R.string.show_login_header) to {
                // 旧实现是 alert 弹窗 + 复制按钮；这里改为状态驱动，由 Composable 渲染确认弹窗
                showHeaderDialog = source?.getLoginHeader()
            },
            getString(R.string.del_login_header) to { source?.removeLoginHeader() },
            getString(R.string.log) to { showDialogFragment<AppLogDialog>() },
        )
    }

    internal var showHeaderDialog: String? by mutableStateOf(null)

    // ---------- WebView 登录支路（原 WebViewLoginFragment） ----------

    @SuppressLint("SetJavaScriptEnabled")
    internal fun createLoginWebView(): WebView {
        val source = viewModel.source
        val webView = WebViewPool.acquire(this).also { pooledWebView = it }.realWebView
        webView.onResume()
        webView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            viewModel.headerMap[AppConst.UA_NAME]?.let { userAgentString = it }
        }
        val cookieManager = CookieManager.getInstance()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (source != null) {
                    CookieStore.setCookie(source.getKey(), cookieManager.getCookie(url))
                }
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (source != null) {
                    CookieStore.setCookie(source.getKey(), cookieManager.getCookie(url))
                }
                if (checking) finish()
                super.onPageFinished(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = shouldOverride(request.url)

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                shouldOverride(url.toUri())

            private fun shouldOverride(url: Uri): Boolean {
                return when (url.scheme) {
                    "http", "https" -> false
                    else -> {
                        window.decorView.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                            openUrl(url)
                        }
                        true
                    }
                }
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?
            ) {
                handler?.proceed()
            }
        }
        source?.let { loadLoginUrl(it, webView) }
        return webView
    }

    private fun loadLoginUrl(source: BaseSource, webView: WebView? = null) {
        val loginUrl = source.loginUrl ?: return
        val absoluteUrl = NetworkUtils.getAbsoluteURL(source.getKey(), loginUrl)
        (webView ?: pooledWebView?.realWebView)?.loadUrl(absoluteUrl, viewModel.headerMap)
    }

    /** 对应旧 WebViewLoginFragment 的 menu_ok */
    internal fun checkCookieAndFinish() {
        if (checking) return
        checking = true
        toastOnUi(R.string.check_host_cookie)
        viewModel.source?.let { loadLoginUrl(it) }
    }

    override fun onDestroy() {
        // 兜底持久化（对应旧 onDismiss）：点过保存就不重复写
        if (!oKToClose && hasChange) {
            val loginInfo = viewModel.loginInfo
            if (loginInfo.isEmpty()) {
                viewModel.source?.removeLoginInfo()
            } else {
                viewModel.source?.putLoginInfo(GSON.toJson(loginInfo))
            }
        }
        pooledWebView?.let { WebViewPool.release(it) }
        pooledWebView = null
        super.onDestroy()
    }

    internal fun finishSafely() {
        // 配置变更重建时不关闭宿主（旧代码 isChangingConfigurations 守卫的等价处理）
        if (!isChangingConfigurations) finish()
    }

    /** 输入防抖（对应旧 600ms Handler） */
    internal fun scheduleAction(state: RowState) {
        val action = state.action ?: return
        lifecycleScope.launch {
            delay(600)
            handleButtonClick(action, state.name, false)
        }
    }

    companion object {
        fun start(context: Context, sourceUrl: String, sourceType: String) {
            context.startActivity<HostSourceLoginActivity> {
                putExtra("type", sourceType)
                putExtra("key", sourceUrl)
            }
        }

        fun startIntent(context: Context, sourceUrl: String, sourceType: String): Intent =
            Intent(context, HostSourceLoginActivity::class.java).apply {
                putExtra("type", sourceType)
                putExtra("key", sourceUrl)
            }
    }
}

/** 表单项状态：替代旧 flexbox 子 View */
internal class RowState(
    val name: String,
    val type: String,
    val action: String?,
    val chars: List<String>,
    val default: String?,
    val justify: String?,
) {
    var value by mutableStateOf("")
    var label by mutableStateOf("")
    var selectedIndex by mutableIntStateOf(0)
}

@Composable
private fun HostSourceLoginScreen(activity: HostSourceLoginActivity) {
    activity.showHeaderDialog?.let { header ->
        io.legado.app.uikit.v5.components.AppConfirmDialog(
            title = stringResource(R.string.login_header),
            message = header,
            confirmLabel = stringResource(R.string.copy_text),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                activity.showHeaderDialog = null
                activity.sendToClip(header)
            },
            onDismiss = { activity.showHeaderDialog = null },
        )
    }

    when (activity.webLogin) {
        null -> {
            // 数据未就绪
            return
        }

        true -> {
            HostV5Page(
                title = activity.titleText,
                onBack = { activity.finishSafely() },
                trailing = {
                    io.legado.app.uikit.v5.components.AppFilledButton(
                        text = stringResource(R.string.ok),
                        onClick = { activity.checkCookieAndFinish() },
                    )
                },
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = {
                        FrameLayout(it).apply {
                            addView(
                                activity.createLoginWebView(),
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    },
                )
            }
        }

        false -> {
            val actions = rememberCombined(activity.rows.size, activity.hasChange) {
                activity.buildMenuActions()
            }
            HostV5EditPage(
                title = activity.titleText,
                dirty = activity.hasChange,
                onSave = { activity.submitLogin() },
                onDiscard = { activity.finishSafely() },
                menuActions = actions,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    activity.rows.forEach { row ->
                        RowUiItem(activity, row)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowUiItem(activity: HostSourceLoginActivity, row: RowState) {
    when (row.type) {
        RowUi.Type.password -> {
            io.legado.app.uikit.v5.components.AppTextField(
                value = row.value,
                onValueChange = {
                    row.value = it
                    activity.hasChange = true
                    activity.scheduleAction(row)
                },
                placeholder = row.label,
                singleLine = true,
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        RowUi.Type.button -> {
            io.legado.app.uikit.v5.components.AppFilledButton(
                text = row.label,
                onClick = {
                    activity.handleButtonClick(row.action, row.name, false)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        RowUi.Type.toggle -> {
            // toggle 是 chars 循环取值（非布尔），用列表项点击轮换
            io.legado.app.uikit.v5.components.AppListItem(
                headline = row.label,
                supporting = row.value,
                onClick = {
                    if (row.chars.isNotEmpty()) {
                        val next = (row.chars.indexOf(row.value) + 1) % row.chars.size
                        row.value = row.chars[next]
                        activity.viewModel.loginInfo[row.name] = row.value
                        activity.hasChange = true
                        activity.handleButtonClick(row.action, row.name, false)
                    }
                },
            )
        }

        RowUi.Type.select -> {
            var open by androidx.compose.runtime.remember { mutableStateOf(false) }
            io.legado.app.uikit.v5.components.AppListItem(
                headline = row.label,
                supporting = row.chars.getOrNull(row.selectedIndex) ?: row.value,
                onClick = { open = true },
            )
            if (open) {
                io.legado.app.uikit.v5.components.AppMenuSheet(
                    title = row.label,
                    actions = row.chars.mapIndexed { index, char ->
                        char to {
                            open = false
                            row.selectedIndex = index
                            row.value = char
                            activity.viewModel.loginInfo[row.name] = char
                            activity.hasChange = true
                            activity.handleButtonClick(row.action, row.name, false)
                        }
                    },
                    onDismiss = { open = false },
                )
            }
        }

        else -> {
            // Type.text（含未知类型按文本框处理）
            io.legado.app.uikit.v5.components.AppTextField(
                value = row.value,
                onValueChange = {
                    row.value = it
                    activity.hasChange = true
                    activity.scheduleAction(row)
                },
                placeholder = row.label,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun rememberCombined(vararg keys: Any?, block: () -> List<Pair<String, () -> Unit>>) =
    androidx.compose.runtime.remember(*keys) { block() }
