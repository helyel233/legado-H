package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.dns.DnsBenchmark
import io.legado.app.help.http.dns.DnsConfigError
import io.legado.app.help.http.dns.DnsConfigException
import io.legado.app.help.http.dns.DnsDomainRule
import io.legado.app.help.http.dns.DnsHosts
import io.legado.app.help.http.dns.DnsNames
import io.legado.app.help.http.dns.DnsProbeError
import io.legado.app.help.http.dns.DnsProbeResult
import io.legado.app.help.http.dns.DnsScope
import io.legado.app.help.http.dns.DohProvider
import io.legado.app.help.http.dns.NetworkDnsConfig
import io.legado.app.help.http.dns.NetworkDnsStore
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.restart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The same page renderer, preferences, dialogs, theme and search navigation as
 * the other settings screens; no second UI component system.
 *
 * 文案一律从 strings.xml 取（`dns_*`）：这一页几乎全是文案，硬编码中文会让
 * 英文环境下整页只有这一屏是中文，而设置页是用户最容易撞见语言不一致的地方。
 */
class NetworkDnsConfigFragment : ComposeSettingFragment() {
    override val titleRes: Int = R.string.network_dns_title
    override val autoOpenTargetItem: Boolean = false
    private var benchmarkJob: Job? = null
    private var benchmarkGeneration = 0L
    private var results = emptyList<DnsProbeResult>()
    private var progress = ""
    private var lastError: String? = null
    private var applyingSettings = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (NetworkDnsStore.hasInvalidSettings(ctx)) {
            lastError = str(R.string.dns_error_invalid_saved)
        }
    }

    override fun onDestroyView() {
        cancelBenchmark()
        super.onDestroyView()
    }

    private fun cancelBenchmark() {
        benchmarkGeneration++
        benchmarkJob?.cancel()
        benchmarkJob = null
        progress = ""
    }

    override fun buildPageSpec(): SettingPageSpec {
        val config = NetworkDnsStore.load(ctx)
        val selected = DohProvider.presets.find { it.url == config.endpoint && it.bootstrap == config.bootstrap }
        val running = benchmarkJob?.isActive == true
        val sections = mutableListOf(
            SettingSectionSpec(items = listOf(
                SettingSwitchSpec(PreferKey.networkDnsConfig, str(R.string.dns_doh_enabled_title), config.enabled,
                    { save(config.copy(enabled = it)) }, str(R.string.dns_doh_enabled_summary)),
                action("dns_provider", str(R.string.dns_provider_title),
                    selected?.let { str(it.nameRes) } ?: str(R.string.dns_provider_custom), ::chooseProvider),
                action("dns_custom", str(R.string.dns_endpoint_title),
                    selected?.url ?: str(R.string.dns_endpoint_saved_custom), ::editEndpoint),
                action("dns_restart", str(R.string.dns_restart_title),
                    lastError ?: str(R.string.dns_restart_summary)) {
                    showComposeConfirmDialog(
                        str(R.string.dns_restart_dialog_title),
                        str(R.string.dns_restart_dialog_message),
                        onPositive = ::restartWithSavedSettings
                    )
                }
            )),
            SettingSectionSpec(title = str(R.string.dns_section_by_feature), items = DnsScope.entries.map { scope ->
                SettingSwitchSpec("dns_scope_${scope.key}", str(scope.titleRes), scope in config.scopes,
                    { checked -> save(config.copy(scopes = if (checked) config.scopes + scope else config.scopes - scope)) },
                    str(if (scope in config.scopes) R.string.dns_scope_on_summary else R.string.dns_scope_off_summary))
            }),
            SettingSectionSpec(title = str(R.string.dns_section_domain_exceptions), items = listOf(
                action("dns_rule_add", str(R.string.dns_rule_add_title), str(R.string.dns_rule_add_summary)) {
                    editRule(null)
                }
            ) + config.rules.map { rule ->
                action(
                    "dns_rule_${rule.pattern}",
                    rule.pattern,
                    str(if (rule.doh) R.string.dns_rule_use_doh else R.string.dns_scope_off_summary)
                ) { editRule(rule) }
            }),
            SettingSectionSpec(title = str(R.string.dns_section_benchmark), items = buildList {
                add(action("dns_test_host", str(R.string.dns_test_host_title), stringSetting(TEST_HOST, DEFAULT_TEST_HOST)) {
                    editTestHost()
                })
                add(action("dns_benchmark", str(if (running) R.string.dns_benchmark_stop else R.string.dns_benchmark_start),
                    if (running) progress else str(R.string.dns_benchmark_summary)) {
                    if (running) { cancelBenchmark(); refreshSettings() } else startBenchmark()
                })
                results.forEach { result ->
                    add(action(
                        "dns_result_${result.provider?.id ?: "system"}",
                        resultTitle(result),
                        str(
                            R.string.dns_result_summary,
                            result.successCount,
                            result.samples.size,
                            ms(result.firstMilliseconds),
                            ms(result.medianMilliseconds)
                        )
                    ) { showResult(result) })
                }
            }),
            SettingSectionSpec(title = str(R.string.dns_section_advanced), items = listOf(
                toggle("dns_ipv6", R.string.dns_ipv6_title, R.string.dns_ipv6_summary, config.includeIpv6) {
                    save(config.copy(includeIpv6 = it))
                },
                toggle("dns_local", R.string.dns_local_title, R.string.dns_local_summary, config.localNamesUseSystem) {
                    save(config.copy(localNamesUseSystem = it))
                },
                toggle("dns_fallback", R.string.dns_fallback_title, R.string.dns_fallback_summary, config.fallbackToSystem) {
                    save(config.copy(fallbackToSystem = it))
                },
                action(PreferKey.customHosts, str(R.string.dns_hosts_title), str(R.string.dns_hosts_summary), ::editHosts),
                // 「优先使用 Cronet」不是 DNS 的子项（它是全局网络栈选择），已移到
                // 网络设置 › 连接与请求区，与 User-Agent、线程数并列
                action("dns_help", str(R.string.dns_help_title), str(R.string.dns_help_summary), ::showHelp)
            ))
        )
        return SettingPageSpec(titleRes, sections)
    }

    private fun action(key: String, title: String, summary: String, click: () -> Unit): SettingItemSpec =
        SettingActionSpec(key = key, title = title, summary = summary, onClick = click)

    private fun toggle(
        key: String,
        @StringRes titleRes: Int,
        @StringRes summaryRes: Int,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ): SettingItemSpec = SettingSwitchSpec(
        key = key,
        title = str(titleRes),
        summary = str(summaryRes),
        checked = checked,
        onCheckedChange = onCheckedChange
    )

    private fun save(config: NetworkDnsConfig) {
        runCatching { NetworkDnsStore.save(ctx, config) }
            .onSuccess { lastError = null }
            .onFailure { error ->
                lastError = (error as? DnsConfigException)?.let { validationText(it.error) }
                    ?: str(R.string.dns_error_invalid)
            }
        refreshSettings()
    }

    private fun restartWithSavedSettings() {
        if (applyingSettings) return
        applyingSettings = true
        val context = ctx
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { NetworkDnsStore.persistBeforeRestart(context) }
                if (saved) context.restart() else {
                    lastError = str(R.string.dns_error_write_failed)
                    refreshSettings()
                }
            } finally { applyingSettings = false }
        }
    }

    private fun chooseProvider() {
        val config = NetworkDnsStore.load(ctx)
        showComposeChoiceListDialog(
            str(R.string.dns_provider_title),
            DohProvider.presets.map { str(it.nameRes) } + str(R.string.dns_provider_custom_option),
            selectedIndex = DohProvider.presets.indexOfFirst { it.url == config.endpoint && it.bootstrap == config.bootstrap }
                .let { if (it < 0) DohProvider.presets.size else it },
            onSelected = { index ->
                DohProvider.presets.getOrNull(index)?.let { save(config.copy(endpoint = it.url, bootstrap = it.bootstrap)) } ?: editEndpoint()
            })
    }

    private fun editEndpoint() {
        val config = NetworkDnsStore.load(ctx)
        fun candidate(values: List<String>): NetworkDnsConfig = config.copy(endpoint = values[0].trim(),
            bootstrap = values[1].split(Regex("[,，\\s]+")).filter { it.isNotBlank() })
        showComposeTextFormDialog(
            str(R.string.dns_provider_title),
            labels = listOf(
                str(R.string.dns_endpoint_url_label),
                str(R.string.dns_endpoint_bootstrap_label)
            ),
            initialValues = listOf(config.endpoint, config.bootstrap.joinToString(", ")),
            message = str(R.string.dns_endpoint_message),
            validateInput = { values -> runCatching { candidate(values).validated() }.isSuccess },
            onPositive = { save(candidate(it)) })
    }

    private fun editRule(original: DnsDomainRule?) {
        showComposeTextInputDialog(
            str(R.string.dns_rule_dialog_title),
            hint = "example.com / *.example.com",
            initialValue = original?.pattern.orEmpty(),
            message = str(R.string.dns_rule_message),
            validateInput = { runCatching { DnsDomainRule(it, false).normalized() }.isSuccess },
            neutralText = if (original == null) null else str(R.string.dns_rule_delete),
            onNeutral = if (original == null) null else { {
                val config = NetworkDnsStore.load(ctx)
                save(config.copy(rules = config.rules.filterNot { it.pattern == original.pattern }))
            } },
            onPositive = { pattern ->
                showComposeChoiceListDialog(
                    str(R.string.dns_rule_use_title, pattern.trim()),
                    listOf("DoH", str(R.string.dns_scope_system_name)),
                    selectedIndex = if (original?.doh != false) 0 else 1,
                    onSelected = { index ->
                        val rule = DnsDomainRule(pattern, index == 0).normalized()
                        val config = NetworkDnsStore.load(ctx)
                        save(config.copy(rules = config.rules.filterNot { it.pattern == original?.pattern || it.pattern == rule.pattern } + rule))
                    })
            })
    }

    private fun editHosts() {
        showComposeTextInputDialog(
            str(R.string.dns_hosts_title),
            initialValue = stringSetting(PreferKey.customHosts, ""),
            hint = "{\"example.com\": [\"192.0.2.1\"]}",
            minLines = 4,
            maxLines = 12,
            message = str(R.string.dns_hosts_message),
            validateInput = { runCatching { DnsHosts.parse(it, strict = true) }.isSuccess },
            onPositive = { updateStringSetting(PreferKey.customHosts, it.trim()) })
    }

    private fun editTestHost() {
        showComposeTextInputDialog(
            str(R.string.dns_test_host_title),
            initialValue = stringSetting(TEST_HOST, DEFAULT_TEST_HOST),
            validateInput = { DnsNames.host(it)?.let { host -> DnsNames.literal(host) == null } == true },
            onPositive = {
                cancelBenchmark()
                updateStringSetting(TEST_HOST, requireNotNull(DnsNames.host(it)))
                results = emptyList()
                refreshSettings()
            })
    }

    private fun startBenchmark() {
        if (benchmarkJob != null) return
        val generation = ++benchmarkGeneration
        val config = NetworkDnsStore.load(ctx)
        val host = stringSetting(TEST_HOST, DEFAULT_TEST_HOST)
        results = emptyList()
        benchmarkJob = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (provider in DnsBenchmark.providers(config)) {
                    progress = str(
                        R.string.dns_benchmark_progress,
                        provider?.let { str(it.nameRes) } ?: str(R.string.dns_scope_system_name),
                        host
                    )
                    refreshSettings()
                    val result = runInterruptible(Dispatchers.IO) { DnsBenchmark.measure(config, provider, host) }
                    if (generation == benchmarkGeneration) results = results + result
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == benchmarkGeneration) lastError = str(R.string.dns_error_benchmark_failed)
            } finally {
                if (generation == benchmarkGeneration) {
                    benchmarkJob = null
                    progress = ""
                    if (view != null) refreshSettings()
                }
            }
        }
        benchmarkJob?.start()
        refreshSettings()
    }

    private fun showResult(result: DnsProbeResult) {
        val details = str(R.string.dns_result_details_header, result.hostname) +
            result.samples.mapIndexed { index, sample ->
                str(
                    R.string.dns_result_sample_line,
                    index + 1,
                    sample.milliseconds?.let {
                        str(R.string.dns_result_sample_detail, ms(it), sample.addressCount)
                    } ?: noticeText(sample.error)
                )
            }.joinToString("\n")
        val provider = result.provider
        showComposeActionListDialog(
            resultTitle(result),
            if (provider == null) {
                listOf(str(R.string.close))
            } else {
                listOf(str(R.string.dns_result_choose), str(R.string.close))
            },
            message = details,
            onSelected = { index ->
                if (index == 0 && provider != null) {
                    val config = NetworkDnsStore.load(ctx)
                    save(config.copy(endpoint = provider.url, bootstrap = provider.bootstrap))
                }
            })
    }

    private fun resultTitle(result: DnsProbeResult): String =
        result.provider?.let { str(it.nameRes) } ?: str(R.string.dns_scope_system_name)

    /** 单次探测失败（超时/证书/队列繁忙）在结果详情里占一行，用一句话说明原因。 */
    private fun noticeText(error: DnsProbeError?): String = str(when (error) {
        DnsProbeError.BUSY -> R.string.dns_probe_busy
        DnsProbeError.EMPTY -> R.string.dns_probe_empty
        DnsProbeError.TIMEOUT -> R.string.dns_probe_timeout
        DnsProbeError.TLS -> R.string.dns_probe_tls
        DnsProbeError.FAILED, null -> R.string.dns_probe_failed
    })

    /** 校验失败原因 → 提示文案；数据层只给原因枚举，措辞全部在这里。 */
    private fun validationText(error: DnsConfigError): String = str(when (error) {
        DnsConfigError.INVALID_DOMAIN -> R.string.dns_validation_invalid_domain
        DnsConfigError.INVALID_ENDPOINT -> R.string.dns_validation_invalid_endpoint
        DnsConfigError.DNS_QUERY_PARAMETER -> R.string.dns_validation_dns_parameter
        DnsConfigError.TOO_MANY_BOOTSTRAP -> R.string.dns_validation_too_many_bootstrap
        DnsConfigError.INVALID_BOOTSTRAP -> R.string.dns_validation_invalid_bootstrap
        DnsConfigError.TOO_MANY_RULES -> R.string.dns_validation_too_many_rules
        DnsConfigError.DUPLICATE_RULES -> R.string.dns_validation_duplicate_rules
        DnsConfigError.TOO_LARGE -> R.string.dns_validation_too_large
        DnsConfigError.UNSUPPORTED_VERSION -> R.string.dns_validation_unsupported_version
        DnsConfigError.IP_NOT_ALLOWED -> R.string.dns_validation_ip_not_allowed
        DnsConfigError.HOSTS_TOO_LARGE -> R.string.dns_validation_hosts_too_large
        DnsConfigError.HOSTS_NEED_STRING -> R.string.dns_validation_hosts_address
        DnsConfigError.HOSTS_BAD_MAPPING -> R.string.dns_validation_hosts_mapping
        DnsConfigError.HOSTS_DUPLICATE -> R.string.dns_validation_hosts_duplicate
        DnsConfigError.HOSTS_INVALID -> R.string.dns_validation_hosts_invalid
    })

    private fun showHelp() {
        showComposeConfirmDialog(
            str(R.string.dns_help_title),
            message = str(R.string.dns_help_message),
            showNegative = false,
            onPositive = {})
    }

    private fun ms(value: Double?): String =
        value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: str(R.string.dns_result_failed)

    companion object {
        private const val TEST_HOST = "networkDnsTestHost"
        private const val DEFAULT_TEST_HOST = "example.com"
    }
}
