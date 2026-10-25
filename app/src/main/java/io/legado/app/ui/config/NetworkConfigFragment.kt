package io.legado.app.ui.config

import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.LocalNetworkAccess
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CheckSource
import io.legado.app.service.WebService
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
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
                            title = getString(R.string.network_dns_title),
                            summary = getString(R.string.network_dns_summary),
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
                    title = getString(R.string.network_web_service_section),
                    items = listOf(
                        numberAction(
                            key = PreferKey.webPort,
                            title = getString(R.string.web_port_title),
                            summary = getString(R.string.web_port_summary, AppConfig.webPort.toString()),
                            min = 1024,
                            max = 60000,
                            value = AppConfig.webPort,
                            onSelected = { AppConfig.webPort = it }
                        ),
                        SettingActionSpec(
                            key = PreferKey.webServiceToken,
                            title = getString(R.string.web_service_token_title),
                            summary = if (AppConfig.webServiceToken.isBlank()) {
                                getString(R.string.web_service_token_summary_off)
                            } else {
                                getString(R.string.web_service_token_summary_on)
                            },
                            onClick = {
                                showComposeTextFormDialog(
                                    title = getString(R.string.web_service_token_title),
                                    labels = listOf(getString(R.string.web_service_token_hint)),
                                    initialValues = listOf(AppConfig.webServiceToken),
                                    positiveText = getString(android.R.string.ok),
                                    negativeText = getString(R.string.cancel),
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
                                            WebService.stop(requireContext())
                                            WebService.start(requireContext())
                                        }
                                    }
                                )
                            }
                        ),
                        switch(
                            key = PreferKey.webServiceWakeLock,
                            title = getString(R.string.web_service_wake_lock),
                            summary = getString(R.string.web_service_wake_lock_summary),
                            defaultValue = false
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.network_connection_section),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.userAgent,
                            title = getString(R.string.user_agent),
                            summary = userAgentValue(),
                            onClick = ::showUserAgentDialog
                        ),
                        numberAction(
                            key = PreferKey.threadCount,
                            title = getString(R.string.threads_num_title),
                            summary = getString(R.string.threads_num, AppConfig.threadCount.toString()),
                            min = 1,
                            max = 999,
                            value = AppConfig.threadCount,
                            onSelected = { AppConfig.threadCount = it }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.network_source_upload_section),
                    items = listOf(
                        SettingActionSpec(
                            key = PreferKey.checkSource,
                            title = getString(R.string.check_source_config),
                            summary = CheckSource.summary,
                            onClick = { showDialogFragment<CheckSourceConfig>() }
                        ),
                        SettingActionSpec(
                            key = PreferKey.uploadRule,
                            title = getString(R.string.direct_link_upload_rule),
                            summary = getString(R.string.direct_link_upload_rule_summary),
                            onClick = { showDialogFragment<ComposeDirectLinkUploadDialog>() }
                        )
                    )
                )
            )
        )
    }


    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.threadCount -> postEvent(PreferKey.threadCount, "")

            PreferKey.webPort -> {
                if (WebService.isRun) {
                    WebService.stop(requireContext())
                    LocalNetworkAccess.request(
                        requireContext(),
                        onGranted = { WebService.start(requireContext()) }
                    )
                }
            }
        }
    }

    private fun showUserAgentDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.user_agent),
            hint = getString(R.string.user_agent),
            initialValue = userAgentValue(),
            onPositive = { userAgent ->
                if (userAgent.isNullOrBlank()) {
                    removePref(PreferKey.userAgent)
                } else {
                    putPrefString(PreferKey.userAgent, userAgent)
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
