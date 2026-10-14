package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import io.legado.app.uikit.v5.components.AppConfirmDialog
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppFilterDialog
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppInputDialog
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.dns.DnsBenchmark
import io.legado.app.help.http.dns.DnsDomainRule
import io.legado.app.help.http.dns.DnsHosts
import io.legado.app.help.http.dns.DnsNames
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
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.restart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.Locale

/** The same page renderer, preferences, dialogs, theme and search navigation as
 * the other settings screens; no second UI component system. */
class NetworkDnsConfigFragment : ComposeSettingFragment() {
    override val titleRes: Int = R.string.network_dns_title
    override val autoOpenTargetItem: Boolean = false

    // ── V5 弹窗状态 ─────────────────────────────────────────────
    internal var restartConfirmOpen by mutableStateOf(false)
    internal var providerPickerOpen by mutableStateOf(false)
    internal var endpointEditOpen by mutableStateOf(false)
    internal var endpointUrlText by mutableStateOf("")
    internal var endpointIpText by mutableStateOf("")
    internal var ruleEditOpen by mutableStateOf(false)
    internal var ruleEditText by mutableStateOf("")
    internal var ruleEditOriginal by mutableStateOf<DnsDomainRule?>(null)
    internal var ruleDohOpen by mutableStateOf(false)
    internal var ruleDohPattern by mutableStateOf("")
    internal var ruleDohOriginal by mutableStateOf<DnsDomainRule?>(null)
    internal var ruleDohIndex by mutableStateOf(0)
    internal var hostsEditOpen by mutableStateOf(false)
    internal var hostsText by mutableStateOf("")
    internal var testHostOpen by mutableStateOf(false)
    internal var testHostText by mutableStateOf("")
    internal var resultDialogTarget by mutableStateOf<DnsProbeResult?>(null)
    internal var helpOpen by mutableStateOf(false)

    private var benchmarkJob: Job? = null
    private var benchmarkGeneration = 0L
    private var results = emptyList<DnsProbeResult>()
    private var progress = ""
    private var lastError: String? = null
    private var applyingSettings = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (NetworkDnsStore.hasInvalidSettings(requireContext())) lastError = "已保存的 DNS 配置无效，当前使用系统 DNS，请重新保存配置。"
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
        val config = NetworkDnsStore.load(requireContext())
        val selected = DohProvider.presets.find { it.url == config.endpoint && it.bootstrap == config.bootstrap }
        val running = benchmarkJob?.isActive == true
        val sections = mutableListOf(
            SettingSectionSpec(items = listOf(
                SettingSwitchSpec(PreferKey.networkDnsConfig, "启用 DoH", config.enabled,
                    { save(config.copy(enabled = it)) }, "保存后重启生效；未启用时跟随系统 DNS"),
                action("dns_provider", "DoH 服务", selected?.name ?: "自定义服务", ::chooseProvider),
                action("dns_custom", "服务地址与启动 IP", selected?.url ?: "已保存自定义 HTTPS 地址", ::editEndpoint),
                action("dns_restart", "重启并应用", lastError ?: "分流规则、Hosts 和网络组件在重启后一起生效") {
                    restartConfirmOpen = true
                }
            )),
            SettingSectionSpec(title = "按功能选择", items = DnsScope.entries.map { scope ->
                SettingSwitchSpec("dns_scope_${scope.key}", scope.title, scope in config.scopes,
                    { checked -> save(config.copy(scopes = if (checked) config.scopes + scope else config.scopes - scope)) },
                    if (scope in config.scopes) "DoH 开启后使用 DoH" else "使用系统 DNS")
            }),
            SettingSectionSpec(title = "域名例外", items = listOf(
                action("dns_rule_add", "添加域名规则", "可为域名指定 DoH 或系统 DNS，优先于功能选择") { editRule(null) }
            ) + config.rules.map { rule ->
                action("dns_rule_${rule.pattern}", rule.pattern, if (rule.doh) "使用 DoH" else "使用系统 DNS") { editRule(rule) }
            }),
            SettingSectionSpec(title = "DNS 测速", items = buildList {
                add(action("dns_test_host", "测试域名", stringSetting(TEST_HOST, "example.com")) { editTestHost() })
                add(action("dns_benchmark", if (running) "停止测速" else "开始测速",
                    if (running) progress else "系统 DNS 与各 DoH 服务各查询 3 次，只比较解析耗时") {
                    if (running) { cancelBenchmark(); refreshSettings() } else startBenchmark()
                })
                results.forEach { result ->
                    add(action("dns_result_${result.provider?.id ?: "system"}", result.name,
                        "成功 ${result.successCount}/${result.samples.size} · 首次 ${ms(result.firstMilliseconds)} · 中位数 ${ms(result.medianMilliseconds)}") {
                        showResult(result)
                    })
                }
            }),
            SettingSectionSpec(title = "高级", items = listOf(
                SettingSwitchSpec("dns_ipv6", "查询 IPv6 地址", config.includeIpv6, { save(config.copy(includeIpv6 = it)) }, "同时查询 A 和 AAAA；系统 DNS 由系统决定"),
                SettingSwitchSpec("dns_local", "局域网名称使用系统 DNS", config.localNamesUseSystem, { save(config.copy(localNamesUseSystem = it)) }, "单标签域名、localhost、.local、.lan 等；域名例外可覆盖"),
                SettingSwitchSpec("dns_fallback", "DoH 失败时回退系统 DNS", config.fallbackToSystem, { save(config.copy(fallbackToSystem = it)) }, "关闭时解析失败会报错；测速始终不回退"),
                action(PreferKey.customHosts, "自定义 Hosts", "指定地址优先于 DoH 和域名规则", ::editHosts),
                SettingSwitchSpec(PreferKey.cronet, "优先使用 Cronet", booleanSetting(PreferKey.cronet, false),
                    { updateBooleanSetting(PreferKey.cronet, it) }, "DoH、Hosts、指定 IP 或显式代理请求自动使用 OkHttp；重启后生效"),
                action("dns_help", "覆盖范围与测速说明", "WebView、代理、书源指定 IP 和系统 DNS 的区别", ::showHelp)
            ))
        )
        return SettingPageSpec(titleRes, sections)
    }

    private fun action(key: String, title: String, summary: String, click: () -> Unit): SettingItemSpec =
        SettingActionSpec(key = key, title = title, summary = summary, onClick = click)

    private fun save(config: NetworkDnsConfig) {
        runCatching { NetworkDnsStore.save(requireContext(), config) }
            .onSuccess { lastError = null }
            .onFailure { lastError = it.message ?: "DNS 配置无效" }
        refreshSettings()
    }

    private fun restartWithSavedSettings() {
        if (applyingSettings) return
        applyingSettings = true
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { NetworkDnsStore.persistBeforeRestart(context) }
                if (saved) context.restart() else {
                    lastError = "设置未能写入，请稍后重试。"
                    refreshSettings()
                }
            } finally { applyingSettings = false }
        }
    }

    private fun chooseProvider() {
        providerPickerOpen = true
    }

    internal fun onProviderSelected(index: Int) {
        providerPickerOpen = false
        val config = NetworkDnsStore.load(requireContext())
        DohProvider.presets.getOrNull(index)?.let { save(config.copy(endpoint = it.url, bootstrap = it.bootstrap)) }
            ?: editEndpoint()
    }

    private fun editEndpoint() {
        val config = NetworkDnsStore.load(requireContext())
        endpointUrlText = config.endpoint
        endpointIpText = config.bootstrap.joinToString(", ")
        endpointEditOpen = true
    }

    internal fun onEndpointConfirm() {
        val config = NetworkDnsStore.load(requireContext())
        val candidate = config.copy(
            endpoint = endpointUrlText.trim(),
            bootstrap = endpointIpText.split(Regex("[,，\\s]+")).filter { it.isNotBlank() }
        )
        if (runCatching { candidate.validated() }.isFailure) {
            toastOnUi("DNS 配置无效")
            return
        }
        endpointEditOpen = false
        save(candidate)
    }

    private fun editRule(original: DnsDomainRule?) {
        ruleEditOriginal = original
        ruleEditText = original?.pattern.orEmpty()
        ruleEditOpen = true
    }

    internal fun onRuleEditDelete() {
        val original = ruleEditOriginal ?: return
        ruleEditOpen = false
        val config = NetworkDnsStore.load(requireContext())
        save(config.copy(rules = config.rules.filterNot { it.pattern == original.pattern }))
    }

    internal fun onRuleEditConfirm(pattern: String) {
        ruleEditOpen = false
        ruleDohOriginal = ruleEditOriginal
        ruleDohPattern = pattern
        ruleDohIndex = if (ruleEditOriginal?.doh != false) 0 else 1
        ruleDohOpen = true
    }

    internal fun onRuleDohSelected(index: Int) {
        ruleDohOpen = false
        val pattern = ruleDohPattern
        val original = ruleDohOriginal
        val rule = DnsDomainRule(pattern, index == 0).normalized()
        val config = NetworkDnsStore.load(requireContext())
        save(config.copy(rules = config.rules.filterNot { it.pattern == original?.pattern || it.pattern == rule.pattern } + rule))
    }

    private fun editHosts() {
        hostsText = stringSetting(PreferKey.customHosts, "")
        hostsEditOpen = true
    }

    internal fun onHostsConfirm(text: String) {
        if (runCatching { DnsHosts.parse(text, strict = true) }.isFailure) {
            toastOnUi("Hosts 格式无效")
            return
        }
        hostsEditOpen = false
        updateStringSetting(PreferKey.customHosts, text.trim())
    }

    private fun editTestHost() {
        testHostText = stringSetting(TEST_HOST, "example.com")
        testHostOpen = true
    }

    internal fun onTestHostConfirm(text: String) {
        val host = DnsNames.host(text)?.takeIf { DnsNames.literal(it) == null } ?: run {
            toastOnUi("测试域名无效")
            return
        }
        testHostOpen = false
        cancelBenchmark()
        updateStringSetting(TEST_HOST, host)
        results = emptyList()
        refreshSettings()
    }

    private fun startBenchmark() {
        if (benchmarkJob != null) return
        val generation = ++benchmarkGeneration
        val config = NetworkDnsStore.load(requireContext())
        val host = stringSetting(TEST_HOST, "example.com")
        results = emptyList()
        benchmarkJob = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                for (provider in DnsBenchmark.providers(config)) {
                    progress = "正在查询 ${provider?.name ?: "系统 DNS"} · $host"
                    refreshSettings()
                    val result = runInterruptible(Dispatchers.IO) { DnsBenchmark.measure(config, provider, host) }
                    if (generation == benchmarkGeneration) results = results + result
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == benchmarkGeneration) lastError = "测速未完成，请检查测试域名和网络后重试。"
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
        resultDialogTarget = result
    }

    internal fun onResultAction(index: Int) {
        val result = resultDialogTarget ?: return
        resultDialogTarget = null
        val provider = result.provider
        if (index == 0 && provider != null) {
            val config = NetworkDnsStore.load(requireContext())
            save(config.copy(endpoint = provider.url, bootstrap = provider.bootstrap))
        }
    }

    private fun showHelp() {
        helpOpen = true
    }

    private fun ms(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "失败"

    // ── V5 弹窗挂载 ─────────────────────────────────────────────
    override val extraDialogs: @Composable () -> Unit = {
        val density = LocalVisualSpec.current.density
        if (restartConfirmOpen) {
            AppConfirmDialog(
                title = "重启应用",
                message = "已保存的网络设置将在重启后生效。",
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = { restartConfirmOpen = false; restartWithSavedSettings() },
                onDismiss = { restartConfirmOpen = false },
            )
        }
        if (providerPickerOpen) {
            val config = NetworkDnsStore.load(requireContext())
            AppFilterDialog(
                title = "DoH 服务",
                options = DohProvider.presets.map { it.name } + "自定义",
                selectedIndex = DohProvider.presets.indexOfFirst { it.url == config.endpoint && it.bootstrap == config.bootstrap }
                    .let { if (it < 0) DohProvider.presets.size else it },
                onSelect = { onProviderSelected(it) },
                onDismiss = { providerPickerOpen = false },
            )
        }
        if (endpointEditOpen) {
            AppDialog(onDismiss = { endpointEditOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(density.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = "DoH 服务",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "启动 IP 用于连接 DoH 服务，仍校验原域名证书；留空时可能先由系统解析服务域名。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppTextField(
                        value = endpointUrlText,
                        onValueChange = { endpointUrlText = it },
                        placeholder = "HTTPS 查询地址",
                        singleLine = true,
                    )
                    AppTextField(
                        value = endpointIpText,
                        onValueChange = { endpointIpText = it },
                        placeholder = "启动 IP（可选，多个用逗号分隔）",
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        AppGhostButton(
                            text = stringResource(R.string.cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { endpointEditOpen = false },
                        )
                        AppFilledButton(
                            text = stringResource(R.string.ok),
                            modifier = Modifier.weight(1f),
                            onClick = { onEndpointConfirm() },
                        )
                    }
                }
            }
        }
        if (ruleEditOpen) {
            AppDialog(onDismiss = { ruleEditOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(density.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = "域名规则",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "精确域名优先；通配符只匹配子域名。DoH 总开关关闭时全部跟随系统。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppTextField(
                        value = ruleEditText,
                        onValueChange = { ruleEditText = it },
                        placeholder = "example.com 或 *.example.com",
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        if (ruleEditOriginal != null) {
                            AppGhostButton(
                                text = "删除规则",
                                modifier = Modifier.weight(1f),
                                onClick = { onRuleEditDelete() },
                            )
                        }
                        AppGhostButton(
                            text = stringResource(R.string.cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { ruleEditOpen = false },
                        )
                        AppFilledButton(
                            text = stringResource(R.string.ok),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val pattern = ruleEditText.trim()
                                if (runCatching { DnsDomainRule(pattern, false).normalized() }.isFailure) {
                                    toastOnUi("域名规则无效")
                                } else {
                                    onRuleEditConfirm(pattern)
                                }
                            },
                        )
                    }
                }
            }
        }
        if (ruleDohOpen) {
            AppFilterDialog(
                title = "${ruleDohPattern.trim()} 使用",
                options = listOf("DoH", "系统 DNS"),
                selectedIndex = ruleDohIndex,
                onSelect = { onRuleDohSelected(it) },
                onDismiss = { ruleDohOpen = false },
            )
        }
        if (hostsEditOpen) {
            AppDialog(onDismiss = { hostsEditOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(density.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = "自定义 Hosts",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "保存后重启生效，保留已有 Hosts 格式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppTextField(
                        value = hostsText,
                        onValueChange = { hostsText = it },
                        placeholder = "{\"example.com\": [\"192.0.2.1\"]}",
                        singleLine = false,
                        minLines = 4,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        AppGhostButton(
                            text = stringResource(R.string.cancel),
                            modifier = Modifier.weight(1f),
                            onClick = { hostsEditOpen = false },
                        )
                        AppFilledButton(
                            text = stringResource(R.string.ok),
                            modifier = Modifier.weight(1f),
                            onClick = { onHostsConfirm(hostsText) },
                        )
                    }
                }
            }
        }
        if (testHostOpen) {
            AppInputDialog(
                title = "测试域名",
                value = testHostText,
                onValueChange = { testHostText = it },
                confirmLabel = stringResource(R.string.ok),
                cancelLabel = stringResource(R.string.cancel),
                onConfirm = { onTestHostConfirm(testHostText) },
                onDismiss = { testHostOpen = false },
            )
        }
        resultDialogTarget?.let { result ->
            val provider = result.provider
            val details = "测试域名：${result.hostname}\n" + result.samples.mapIndexed { index, sample ->
                "第 ${index + 1} 次：${sample.milliseconds?.let { "${ms(it)}，${sample.addressCount} 个地址" } ?: sample.error}"
            }.joinToString("\n")
            AppDialog(onDismiss = { resultDialogTarget = null }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(density.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                        if (provider != null) {
                            AppFilledButton(
                                text = "选为 DoH 服务",
                                modifier = Modifier.weight(1f),
                                onClick = { onResultAction(0) },
                            )
                        }
                        AppGhostButton(
                            text = "关闭",
                            modifier = Modifier.weight(1f),
                            onClick = { resultDialogTarget = null },
                        )
                    }
                }
            }
        }
        if (helpOpen) {
            AppDialog(onDismiss = { helpOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(density.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
                ) {
                    Text(
                        text = "覆盖范围与测速说明",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "书源指定 IP、Hosts 优先于域名规则，域名规则优先于功能选择。\n\n" +
                            "分流只控制应用实际发起的原生请求。登录页、WebView 子资源、模板的外部脚本请求、系统或第三方 TTS 不会自动跟随。代理通常负责目标域名解析；应用可能只解析代理服务器。\n\n" +
                            "测速使用当前保存的服务与 IPv6 选项，直接查询域名，不使用 Hosts、业务分流或系统回退。DoH 不使用本地 HTTP 缓存，首次包含建连，复测可复用连接；系统 DNS 的缓存由系统控制。结果是 DNS 查询耗时，不代表下载速度。\n\n" +
                            "网络设置、Hosts、Cronet 保存后重启生效；测速结果不会自动更换服务商。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { helpOpen = false },
                    )
                }
            }
        }
    }

    companion object { private const val TEST_HOST = "networkDnsTestHost" }
}
