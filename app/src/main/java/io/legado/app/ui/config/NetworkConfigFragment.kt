package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LocalNetworkAccess
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CheckSource
import io.legado.app.service.WebService
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.observeEvent
import io.legado.app.utils.openUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 网络设置：DNS/代理、User-Agent、并发线程、Web 服务端口与口令、书源校验与直链上传。
 *
 * DNS 作为三级页挂在最上面（它本身也是一个独立设置页）。
 */
class NetworkConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.network_setting

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingActionSpec(
                            key = "networkDns",
                            title = str(R.string.network_dns_title),
                            summary = str(R.string.network_dns_summary),
                            navigates = true,
                            onClick = {
                                startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.NETWORK_DNS_CONFIG)
                                }
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.network_web_service_section),
                    items = listOf(
                        // 开关放在这里读的是运行时状态，不是偏好值：服务可能因端口被占用
                        // 或本地网络权限被拒而没起来，此时偏好里写着 true 但服务确实没跑
                        SettingSwitchSpec(
                            key = PreferKey.webService,
                            title = str(R.string.web_service),
                            summary = if (WebService.isRun) {
                                WebService.hostAddress
                            } else {
                                str(R.string.web_service_desc)
                            },
                            checked = WebService.isRun,
                            searchKeys = listOf("web服务", "网页服务", "局域网", "web service"),
                            onCheckedChange = ::setWebServiceEnabled
                        ),
                        SettingActionSpec(
                            key = "webServiceCopyAddress",
                            title = str(R.string.copy_text),
                            summary = WebService.hostAddress,
                            visible = WebService.isRun,
                            searchKeys = listOf("复制地址", "web服务地址"),
                            onClick = { ctx.sendToClip(WebService.hostAddress) }
                        ),
                        SettingActionSpec(
                            key = "webServiceOpenBrowser",
                            title = str(R.string.open_in_browser),
                            summary = WebService.hostAddress,
                            visible = WebService.isRun,
                            searchKeys = listOf("浏览器打开", "web服务地址"),
                            onClick = { ctx.openUrl(WebService.hostAddress) }
                        ),
                        numberAction(
                            key = PreferKey.webPort,
                            title = str(R.string.web_port_title),
                            summary = str(R.string.web_port_summary, AppConfig.webPort.toString()),
                            min = 1024,
                            max = 60000,
                            value = AppConfig.webPort,
                            onSelected = { AppConfig.webPort = it }
                        ),
                        SettingActionSpec(
                            key = PreferKey.webServiceToken,
                            title = str(R.string.web_service_token_title),
                            summary = if (AppConfig.webServiceToken.isBlank()) {
                                str(R.string.web_service_token_summary_off)
                            } else {
                                str(R.string.web_service_token_summary_on)
                            },
                            onClick = {
                                showComposeTextFormDialog(
                                    title = str(R.string.web_service_token_title),
                                    labels = listOf(str(R.string.web_service_token_hint)),
                                    initialValues = listOf(AppConfig.webServiceToken),
                                    positiveText = str(android.R.string.ok),
                                    negativeText = str(R.string.cancel),
                                    onPositive = { values ->
                                        val token = values.getOrNull(0)?.trim() ?: ""
                                        // 口令会拼进 URL query 和 Cookie，字符集必须受限：
                                        // 含 & = ; 空格或中文时，地址解析出来的 token 会和设置的
                                        // 对不上（比如 token=a&b 只解析出 a），用户会被锁在 401 外
                                        // 且毫无提示；非 ASCII 还会让部分浏览器直接丢弃 Cookie。
                                        if (token.isNotEmpty() && !token.matches(TOKEN_PATTERN)) {
                                            appCtx.toastOnUi(R.string.web_service_token_invalid)
                                            return@showComposeTextFormDialog
                                        }
                                        AppConfig.webServiceToken = token
                                        if (WebService.isRun) {
                                            // 校验对新请求即时生效，无需重启；
                                            // 但通知/复制地址里的 ?token= 是启动时生成的，
                                            // 重启一次让地址带上新口令，否则用户复制的地址打不开
                                            WebService.stop(ctx)
                                            WebService.start(ctx)
                                        }
                                    }
                                )
                            }
                        ),
                        switch(
                            key = PreferKey.webServiceWakeLock,
                            title = str(R.string.web_service_wake_lock),
                            summary = str(R.string.web_service_wake_lock_summary),
                            defaultValue = false
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.network_connection_section),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.userAgent,
                            title = str(R.string.user_agent),
                            summary = userAgentValue(),
                            onClick = ::showUserAgentDialog
                        ),
                        numberAction(
                            key = PreferKey.threadCount,
                            title = str(R.string.threads_num_title),
                            summary = str(R.string.threads_num, AppConfig.threadCount.toString()),
                            min = 1,
                            max = 999,
                            value = AppConfig.threadCount,
                            onSelected = { AppConfig.threadCount = it }
                        ),
                        // 从 DNS 三级页移来：Cronet 是全局网络栈选择，不是 DoH 的子项，
                        // 和 User-Agent / 线程数同属「连接与请求」
                        switch(
                            key = PreferKey.cronet,
                            title = str(R.string.pref_cronet),
                            summary = str(R.string.pref_cronet_summary),
                            defaultValue = false
                        )
                    )
                ),
                SettingSectionSpec(
                    title = str(R.string.network_source_upload_section),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.checkSource,
                            title = str(R.string.check_source_config),
                            summary = CheckSource.summary,
                            onClick = { showDialogFragment<CheckSourceConfig>() }
                        ),
                        SettingActionSpec(
                            key = PreferKey.uploadRule,
                            title = str(R.string.direct_link_upload_rule),
                            summary = str(R.string.direct_link_upload_rule_summary),
                            onClick = { showDialogFragment<ComposeDirectLinkUploadDialog>() }
                        )
                    )
                )
            )
        )
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 启停是异步的：start() 只发出启动请求，isRun 要等 Service.onCreate 才置真。
        // 光靠点击回调重画会让开关先闪回未开启，这里听服务自己发出的状态事件补一次。
        observeEvent<String>(EventBus.WEB_SERVICE) { refreshSettings() }
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.threadCount -> postEvent(PreferKey.threadCount, "")

            PreferKey.webPort -> {
                if (WebService.isRun) {
                    WebService.stop(ctx)
                    LocalNetworkAccess.request(
                        ctx,
                        onGranted = { WebService.start(ctx) }
                    )
                }
            }
        }
    }

    /**
     * 启停 Web 服务。这是设置体系里唯一的启停入口——原先「我的」页那一行已并入本页，
     * 避免两个地方都能启停同一个服务（下拉快捷开关属于系统入口，不在设置树里）。
     *
     * 启动前先申请本地网络权限：Android 17 起没有它时服务只监听本机，
     * 其它设备连不上，用户会以为服务坏了。
     */
    private fun setWebServiceEnabled(enabled: Boolean) {
        ctx.putPrefBoolean(PreferKey.webService, enabled)
        if (enabled) {
            LocalNetworkAccess.request(
                ctx,
                onGranted = {
                    WebService.start(ctx)
                    refreshSettings()
                },
                // 拒绝授权时服务没有起来，立刻重画，别让开关停在「已开启」
                onDenied = { refreshSettings() }
            )
        } else {
            WebService.stop(ctx)
            refreshSettings()
        }
    }

    private fun showUserAgentDialog() {
        showComposeTextInputDialog(
            title = str(R.string.user_agent),
            hint = str(R.string.user_agent),
            initialValue = userAgentValue(),
            onPositive = { userAgent ->
                if (userAgent.isNullOrBlank()) {
                    removePref(PreferKey.userAgent)
                } else {
                    ctx.putPrefString(PreferKey.userAgent, userAgent)
                }
            }
        )
    }

    private fun userAgentValue(): String {
        return stringSetting(PreferKey.userAgent, "")
            .ifBlank { DEFAULT_USER_AGENT }
    }


    companion object {
        /** Web 服务口令允许的字符集：对 URL query 与 Cookie 双安全（见口令设置项说明） */
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_.@\\-]{4,64}")
        private val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                BuildConfig.Cronet_Main_Version +
                " Safari/537.36"
    }
}
