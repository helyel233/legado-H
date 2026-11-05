package io.legado.app.help.http.dns

import androidx.annotation.StringRes
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.R
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.IDN
import java.net.InetAddress
import java.util.Locale

/**
 * 校验失败的原因。
 *
 * 数据层不持有任何用户可见文案：这里只给原因，文案由设置页从 `dns_validation_*`
 * 取。否则同一句中文会同时出现在数据层（异常 message）和字符串资源里，
 * 英文环境下从异常冒出来的那一份永远是中文。
 */
enum class DnsConfigError {
    INVALID_DOMAIN,
    INVALID_ENDPOINT,
    DNS_QUERY_PARAMETER,
    TOO_MANY_BOOTSTRAP,
    INVALID_BOOTSTRAP,
    TOO_MANY_RULES,
    DUPLICATE_RULES,
    TOO_LARGE,
    UNSUPPORTED_VERSION,
    IP_NOT_ALLOWED,
    HOSTS_TOO_LARGE,
    HOSTS_NEED_STRING,
    HOSTS_BAD_MAPPING,
    HOSTS_DUPLICATE,
    HOSTS_INVALID
}

class DnsConfigException(val error: DnsConfigError) : IllegalArgumentException(error.name)

/**
 * 分流范围。[titleRes] 而不是标题字符串，同 [DnsConfigError]：这一层不知道界面用哪种语言。
 */
enum class DnsScope(val key: String, @param:StringRes val titleRes: Int) {
    READING("reading", R.string.dns_scope_reading),
    IMAGE("image", R.string.dns_scope_image),
    AI("ai", R.string.dns_scope_ai),
    MEDIA("media", R.string.dns_scope_media),
    WEBSOCKET("websocket", R.string.dns_scope_websocket),
    SYNC("sync", R.string.dns_scope_sync),
    IMPORT("import", R.string.dns_scope_import),
    RELAY("relay", R.string.dns_scope_relay),
    OTHER("other", R.string.dns_scope_other)
}

data class DnsDomainRule(val pattern: String, val doh: Boolean) {
    fun matches(host: String): Boolean = if (pattern.startsWith("*.")) {
        host.endsWith(pattern.substring(1)) && host != pattern.substring(2)
    } else host == pattern

    fun normalized(): DnsDomainRule {
        val wildcard = pattern.trim().startsWith("*.")
        val value = DnsNames.host(pattern.trim().removePrefix("*."))
        if (value == null || DnsNames.literal(value) != null) {
            throw DnsConfigException(DnsConfigError.INVALID_DOMAIN)
        }
        return copy(pattern = if (wildcard) "*.$value" else value)
    }
}

data class DohProvider(
    val id: String,
    @param:StringRes val nameRes: Int,
    val url: String,
    val bootstrap: List<String>
) {
    companion object {
        val presets = listOf(
            DohProvider("alidns", R.string.dns_provider_alidns, "https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6")),
            DohProvider("dnspod", R.string.dns_provider_dnspod, "https://doh.pub/dns-query", listOf("1.12.12.12", "120.53.53.53")),
            DohProvider("cloudflare", R.string.dns_provider_cloudflare, "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
            DohProvider("google", R.string.dns_provider_google, "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4"))
        )
    }
}

data class NetworkDnsConfig(
    val enabled: Boolean = false,
    val endpoint: String = DohProvider.presets.first().url,
    val bootstrap: List<String> = DohProvider.presets.first().bootstrap,
    val scopes: Set<DnsScope> = DnsScope.entries.toSet(),
    val rules: List<DnsDomainRule> = emptyList(),
    val fallbackToSystem: Boolean = false,
    val includeIpv6: Boolean = true,
    val localNamesUseSystem: Boolean = true
) {
    fun usesDoh(scope: DnsScope, hostname: String): Boolean {
        if (!enabled) return false
        val host = DnsNames.host(hostname) ?: return false
        if (DnsNames.literal(host) != null) return false
        // Exact rules win; otherwise the longest matching suffix wins. A local
        // domain may explicitly use a private DoH service through an exact rule.
        rules.filter { it.matches(host) }
            .maxWithOrNull(compareBy<DnsDomainRule> { !it.pattern.startsWith("*.") }.thenBy { it.pattern.length })
            ?.let { return it.doh }
        if (localNamesUseSystem && DnsNames.isLocalName(host)) return false
        return scope in scopes
    }

    fun mayUseDoh(scope: DnsScope): Boolean = enabled && (scope in scopes || rules.any { it.doh })

    fun validated(): NetworkDnsConfig {
        val url = endpoint.trim().toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null
        ) {
            throw DnsConfigException(DnsConfigError.INVALID_ENDPOINT)
        }
        if ("dns" in url.queryParameterNames) {
            throw DnsConfigException(DnsConfigError.DNS_QUERY_PARAMETER)
        }
        if (bootstrap.size > MAX_BOOTSTRAP) {
            throw DnsConfigException(DnsConfigError.TOO_MANY_BOOTSTRAP)
        }
        val ips = bootstrap.map { value ->
            val address = DnsNames.literal(value.trim())
                ?: throw DnsConfigException(DnsConfigError.INVALID_BOOTSTRAP)
            address.hostAddress ?: throw DnsConfigException(DnsConfigError.INVALID_BOOTSTRAP)
        }.distinct()
        if (rules.size > MAX_RULES) {
            throw DnsConfigException(DnsConfigError.TOO_MANY_RULES)
        }
        val normalizedRules = rules.map { it.normalized() }
        if (normalizedRules.distinctBy { it.pattern }.size != normalizedRules.size) {
            throw DnsConfigException(DnsConfigError.DUPLICATE_RULES)
        }
        return copy(endpoint = url.toString(), bootstrap = ips, rules = normalizedRules)
    }

    fun encode(): String = JsonObject().apply {
        addProperty("version", 1)
        addProperty("enabled", enabled)
        addProperty("endpoint", endpoint)
        add("bootstrap", JsonArray().also { array -> bootstrap.forEach(array::add) })
        add("scopes", JsonArray().also { array -> scopes.forEach { array.add(it.key) } })
        add("rules", JsonArray().also { array -> rules.forEach { rule ->
            array.add(JsonObject().apply { addProperty("pattern", rule.pattern); addProperty("doh", rule.doh) })
        } })
        addProperty("fallbackToSystem", fallbackToSystem)
        addProperty("includeIpv6", includeIpv6)
        addProperty("localNamesUseSystem", localNamesUseSystem)
    }.toString()

    companion object {
        private const val MAX_BOOTSTRAP = 8
        private const val MAX_RULES = 128
        private const val MAX_ENCODED_LENGTH = 64 * 1024

        fun decode(raw: String?): NetworkDnsConfig {
            if (raw.isNullOrBlank()) return NetworkDnsConfig()
            if (raw.length > MAX_ENCODED_LENGTH) {
                throw DnsConfigException(DnsConfigError.TOO_LARGE)
            }
            val json = JsonParser.parseString(raw).asJsonObject
            if (json.get("version")?.asInt != 1) {
                throw DnsConfigException(DnsConfigError.UNSUPPORTED_VERSION)
            }
            val defaults = NetworkDnsConfig()
            return NetworkDnsConfig(
                enabled = json.get("enabled")?.asBoolean ?: false,
                endpoint = json.get("endpoint")?.asString ?: defaults.endpoint,
                bootstrap = json.getAsJsonArray("bootstrap")?.map { it.asString } ?: defaults.bootstrap,
                scopes = json.getAsJsonArray("scopes")?.mapNotNull { item -> DnsScope.entries.find { it.key == item.asString } }?.toSet() ?: defaults.scopes,
                rules = json.getAsJsonArray("rules")?.map { item ->
                    val rule = item.asJsonObject
                    DnsDomainRule(rule.get("pattern").asString, rule.get("doh").asBoolean)
                } ?: emptyList(),
                fallbackToSystem = json.get("fallbackToSystem")?.asBoolean ?: false,
                includeIpv6 = json.get("includeIpv6")?.asBoolean ?: true,
                localNamesUseSystem = json.get("localNamesUseSystem")?.asBoolean ?: true
            ).validated()
        }
    }
}

object DnsNames {
    fun host(value: String): String? = runCatching {
        val host = value.trim().trimEnd('.').removeSurrounding("[", "]")
        if (literal(host) != null) return@runCatching host.lowercase(Locale.ROOT)
        require(host.isNotEmpty() && host.length <= 253 && host.none { it in "/:@?#*\\%" || it.isWhitespace() })
        // Accept legacy web hosts containing underscores, as OkHttp and the
        // previous system resolver do. Still reject URL delimiters and controls.
        IDN.toASCII(host).lowercase(Locale.ROOT).also { ascii ->
            require(ascii.length <= 253 && ascii.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 && label.all { it in 'a'..'z' || it in '0'..'9' || it in "_-" }
            })
        }
    }.getOrNull()

    fun literal(value: String): InetAddress? {
        val text = value.removeSurrounding("[", "]")
        if (text.contains(':')) {
            if (text.any { it !in "0123456789abcdefABCDEF:." }) return null
            return runCatching { InetAddress.getByName(text) }.getOrNull()
        }
        val parts = text.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { ch -> ch !in '0'..'9' } }) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it !in 0..255 }) return null
        return InetAddress.getByAddress(numbers.map { it.toByte() }.toByteArray())
    }

    fun isLocalName(host: String): Boolean = !host.contains('.') || host == "localhost" ||
        listOf(".localhost", ".local", ".lan", ".internal", ".home.arpa", ".test", ".invalid").any(host::endsWith)
}
