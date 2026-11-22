package io.legado.app.ui.main.homepage

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.repository.HomepageModulesRepository
import io.legado.app.domain.gateway.HomepageModulesGateway
import io.legado.app.domain.model.CustomSetItem
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.domain.model.ModuleItem
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.sortUrls
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SyncSourceResult(
    val hasConfig: Boolean,
    val moduleCount: Int,
)

enum class AutoSetupStatus {
    ADDED,
    NO_SOURCES,
    NO_CATEGORIES,
    CANCELLED,
}

data class AutoSetupResult(
    val status: AutoSetupStatus,
    val addedCount: Int = 0,
)

/**
 * 首页模块构建、样式元数据与跨页面一键固定工具。
 */
internal object HomepageModuleBuilder {

    const val PER_SOURCE_KIND_TIMEOUT_MS = 4_000L
    private const val RESOLVE_CONCURRENCY = 4

    /** 常用模块类型（用于一键添加与快速选择） */
    val quickModuleTypes = listOf(
        HomepageModuleType.Grid,
        HomepageModuleType.Ranking,
        HomepageModuleType.Card,
        HomepageModuleType.Waterfall,
        HomepageModuleType.ButtonGroup,
    )

    @StringRes
    fun styleDescriptionRes(type: HomepageModuleType): Int = when (type) {
        HomepageModuleType.Banner -> R.string.homepage_style_desc_banner
        HomepageModuleType.Ranking -> R.string.homepage_style_desc_ranking
        HomepageModuleType.GridRanking -> R.string.homepage_style_desc_grid_ranking
        HomepageModuleType.Grid -> R.string.homepage_style_desc_grid
        HomepageModuleType.Card -> R.string.homepage_style_desc_card
        HomepageModuleType.InfiniteGrid -> R.string.homepage_style_desc_infinite_grid
        HomepageModuleType.ButtonGroup -> R.string.homepage_style_desc_button_group
        HomepageModuleType.Waterfall -> R.string.homepage_style_desc_waterfall
        HomepageModuleType.Unknown -> R.string.unknown_type
    }

    fun buildExploreModuleDef(
        sourceUrl: String,
        typeKey: String,
        kinds: List<ExploreKind>,
        customTitle: String? = null,
    ): ModuleDef {
        val cleanKinds = kinds.filter { it.title.isNotBlank() }
        val hasAnyUrl = cleanKinds.any { !it.url.isNullOrBlank() }
        val title = customTitle?.takeIf { it.isNotBlank() }
            ?: cleanKinds.joinToString("、") { it.title }
        val keySuffix = cleanKinds.joinToString("_") { it.title }.ifBlank { "default" }.take(48)
        return ModuleDef(
            key = "explore_$keySuffix",
            type = typeKey,
            title = title,
            args = if (hasAnyUrl) {
                GSON.toJson(cleanKinds.map { mapOf("t" to it.title, "u" to (it.url ?: "")) })
            } else {
                null
            },
            url = cleanKinds.firstOrNull()?.url?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
        )
    }

    /** 解析多分类 tab 的 args：[{t:标题, u:URL}] */
    fun parseCategories(args: String?): List<Pair<String, String>>? {
        if (args.isNullOrBlank()) return null
        return runCatching {
            GSON.fromJsonArray<Map<String, String>>(args).getOrDefault(emptyList())
                .mapNotNull { item ->
                    val t = item["t"] ?: return@mapNotNull null
                    t to (item["u"] ?: "")
                }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 解析分类标题（兼容 [{t,u}] 与 ["title"] 两种格式） */
    fun parseKindTitles(args: String?): List<String>? {
        if (args.isNullOrBlank()) return null
        return runCatching {
            val pairs = GSON.fromJsonArray<Map<String, String>>(args).getOrDefault(emptyList())
            if (pairs.isNotEmpty() && pairs.all { it.containsKey("t") }) {
                pairs.mapNotNull { it["t"] }
            } else {
                GSON.fromJsonArray<String>(args).getOrDefault(emptyList())
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 从发现详情页一键固定单个分类（或未传分类 URL 的发现主页）到首页 */
    suspend fun pinExploreCategory(
        sourceUrl: String,
        categoryTitle: String,
        categoryUrl: String,
        moduleType: HomepageModuleType,
    ) = withContext(Dispatchers.IO) {
        val gateway: HomepageModulesGateway =
            HomepageModulesRepository(appDb.homepageModuleDao, appDb.homepageCustomSetDao)
        val sourceName = appDb.bookSourceDao.getBookSource(sourceUrl)?.bookSourceName ?: sourceUrl
        val setId = "src_$sourceUrl"
        if (gateway.getCustomSetById(setId) == null) {
            gateway.upsertCustomSet(CustomSetItem(id = setId, name = sourceName))
        }
        val kindTitle = categoryTitle.ifBlank { sourceName }
        val def = buildExploreModuleDef(
            sourceUrl = sourceUrl,
            typeKey = moduleType.key,
            kinds = listOf(ExploreKind(title = kindTitle, url = categoryUrl.ifBlank { null })),
            customTitle = kindTitle,
        )
        val globalId = ModuleDef.globalIdOf(sourceUrl, def.key, setId)
        val existingCount = gateway.flowBySource(sourceUrl).first().count { it.customSetId == setId }
        val item = ModuleItem(
            id = globalId,
            sourceUrl = sourceUrl,
            moduleKey = def.key,
            type = def.type,
            title = def.title,
            args = def.args,
            url = def.url,
            isEnabled = true,
            customSetId = setId,
            isUserCreated = true,
            sortOrder = existingCount,
            syncedAt = System.currentTimeMillis(),
        )
        gateway.upsertAll(listOf(item))
    }

    /**
     * 一键生成推荐起步模块（有限并发 + 单源超时隔离）：
     * 从启用发现的书源并发解析分类（每源超时受控），若均无有效分类则回退到启用的订阅源。
     */
    suspend fun buildStarterModules(
        bookSources: List<BookSource>,
        rssSources: List<RssSource>,
        perSourceTimeoutMs: Long = PER_SOURCE_KIND_TIMEOUT_MS,
        resolveExploreKinds: suspend (BookSource) -> List<ExploreKind> = { it.exploreKinds() },
        resolveRssKinds: suspend (RssSource) -> List<Pair<String, String>> = { it.sortUrls() },
    ): Pair<List<CustomSetItem>, List<ModuleItem>> = coroutineScope {
        val sets = mutableListOf<CustomSetItem>()
        val modules = mutableListOf<ModuleItem>()
        val now = System.currentTimeMillis()
        val starterTypes = listOf(
            HomepageModuleType.Ranking,
            HomepageModuleType.Grid,
            HomepageModuleType.Card,
            HomepageModuleType.ButtonGroup,
        )

        val semaphore = Semaphore(RESOLVE_CONCURRENCY)
        val resolvedBookKinds = bookSources.map { source ->
            async {
                semaphore.withPermit {
                    withTimeoutOrNull(perSourceTimeoutMs) {
                        try {
                            resolveExploreKinds(source)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    } ?: emptyList()
                }.filter { !it.title.startsWith("ERROR:") && !it.url.isNullOrBlank() }
            }
        }.awaitAll()

        var pickedSourceCount = 0
        for (i in bookSources.indices) {
            if (pickedSourceCount >= 4) break
            val kinds = resolvedBookKinds[i]
            if (kinds.isEmpty()) continue
            val source = bookSources[i]

            val setId = "src_${source.bookSourceUrl}"
            sets.add(CustomSetItem(id = setId, name = source.bookSourceName, sortOrder = sets.size))
            val type = starterTypes[pickedSourceCount % starterTypes.size]
            val maxKinds = if (type == HomepageModuleType.ButtonGroup) 6 else 3
            val pickedKinds = kinds.take(maxKinds)
            val def = buildExploreModuleDef(
                sourceUrl = source.bookSourceUrl,
                typeKey = type.key,
                kinds = pickedKinds,
                customTitle = if (pickedKinds.size == 1) pickedKinds.first().title else source.bookSourceName,
            )
            modules.add(
                ModuleItem(
                    id = ModuleDef.globalIdOf(source.bookSourceUrl, def.key, setId),
                    sourceUrl = source.bookSourceUrl,
                    moduleKey = def.key,
                    type = def.type,
                    title = def.title,
                    args = def.args,
                    url = def.url,
                    isEnabled = true,
                    customSetId = setId,
                    isUserCreated = true,
                    sortOrder = modules.size,
                    syncedAt = now,
                )
            )
            pickedSourceCount++
        }

        if (modules.isEmpty() && rssSources.isNotEmpty()) {
            val rssCandidates = rssSources.take(3)
            val resolvedRssKinds = rssCandidates.map { rss ->
                async {
                    semaphore.withPermit {
                        withTimeoutOrNull(perSourceTimeoutMs) {
                            try {
                                resolveRssKinds(rss)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                emptyList()
                            }
                        } ?: emptyList()
                    }
                }
            }.awaitAll()

            for (i in rssCandidates.indices) {
                val rss = rssCandidates[i]
                val kinds = resolvedRssKinds[i]
                    .filter { it.second.isNotBlank() }
                    .map { (title, url) -> ExploreKind(title = title.ifBlank { rss.sourceName }, url = url) }
                    .ifEmpty { listOf(ExploreKind(title = rss.sourceName, url = rss.sourceUrl)) }
                val setId = "rss_${rss.sourceUrl}"
                sets.add(CustomSetItem(id = setId, name = rss.sourceName, sortOrder = sets.size))
                val pickedKinds = kinds.take(3)
                val def = buildExploreModuleDef(
                    sourceUrl = rss.sourceUrl,
                    typeKey = HomepageModuleType.Card.key,
                    kinds = pickedKinds,
                    customTitle = rss.sourceName,
                )
                modules.add(
                    ModuleItem(
                        id = ModuleDef.globalIdOf(rss.sourceUrl, def.key, setId),
                        sourceUrl = rss.sourceUrl,
                        moduleKey = def.key,
                        type = def.type,
                        title = def.title,
                        args = def.args,
                        url = def.url,
                        isEnabled = true,
                        customSetId = setId,
                        isUserCreated = true,
                        sortOrder = modules.size,
                        syncedAt = now,
                    )
                )
            }
        }
        sets to modules
    }
}
