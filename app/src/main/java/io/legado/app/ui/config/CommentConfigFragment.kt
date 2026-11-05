package io.legado.app.ui.config

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.review.ReviewDownloadConfig
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec

/**
 * 评论抓取与下载。
 *
 * 这批配置原先塞在「高级设置」里（8 项缓存开关 + 6 个默认折叠的下载调优区，约 25 项），
 * 与语言、渲染性能、隐私混在一起。评论下载自成体系（引擎/并发/超时/重试/完整性），
 * 拆成独立页后：高级设置回归「兜底」定位，评论参数也能被设置搜索精准命中。
 */
class CommentConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.review_config_title

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = reviewDownloadSections()
        )
    }

    /** legadoC 评论体系：评论下载各阶段并发/超时/完整性配置，定义集中在 ReviewDownloadConfig。 */
    private fun reviewDownloadSections(): List<SettingSectionSpec> {
        val cfg = ReviewDownloadConfig
        fun numberSpec(setting: ReviewDownloadConfig.Number, help: String) =
            numberAction(
                key = setting.key,
                title = str(setting.title),
                summary = str(
                    R.string.review_download_numeric_summary,
                    setting.value, setting.default, help
                ),
                min = setting.minimum,
                max = setting.maximum,
                value = setting.value,
                onSelected = { setting.value = it }
            )
        return listOf(
            SettingSectionSpec(
                title = str(R.string.review_cache_section),
                items = listOf(
                    switch(
                        key = PreferKey.syncCacheReview,
                        title = str(R.string.menu_sync_cache_review),
                        summary = str(R.string.ps_sync_cache_review),
                        defaultValue = true
                    ),
                    switch(
                        key = PreferKey.cacheReviewSilent,
                        title = str(R.string.cache_review_silent),
                        summary = str(R.string.cache_review_silent_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.autoDownloadReview,
                        title = str(R.string.menu_auto_download_review),
                        summary = str(R.string.ps_auto_download_review),
                        defaultValue = false,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.cacheReviewReplies,
                        title = str(R.string.cache_review_replies),
                        summary = str(R.string.cache_review_replies_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.cacheReviewAvatars,
                        title = str(R.string.cache_review_avatars),
                        summary = str(R.string.cache_review_resource_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.cacheReviewImages,
                        title = str(R.string.cache_review_images),
                        summary = str(R.string.cache_review_resource_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.compressReviewAvatars,
                        title = str(R.string.compress_review_avatars),
                        summary = str(R.string.cache_review_resource_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    switch(
                        key = PreferKey.compressReviewImages,
                        title = str(R.string.compress_review_images),
                        summary = str(R.string.compress_review_images_summary),
                        defaultValue = true,
                        visible = booleanSetting(PreferKey.syncCacheReview, true)
                    ),
                    numberAction(
                        key = PreferKey.reviewAvatarCompressionMaxKb,
                        title = str(R.string.review_avatar_compression_max),
                        summary = str(
                            R.string.review_compression_max_summary,
                            AppConfig.reviewAvatarCompressionMaxKb,
                            AppConfig.DEFAULT_REVIEW_AVATAR_COMPRESSION_KB,
                            str(R.string.review_download_help_avatar_compression)
                        ),
                        min = AppConfig.MIN_REVIEW_COMPRESSION_KB,
                        max = 1024,
                        value = AppConfig.reviewAvatarCompressionMaxKb,
                        onSelected = { AppConfig.reviewAvatarCompressionMaxKb = it },
                        visible = booleanSetting(PreferKey.syncCacheReview, true) and
                            booleanSetting(PreferKey.compressReviewAvatars, true)
                    ),
                    numberAction(
                        key = PreferKey.reviewImageCompressionMaxKb,
                        title = str(R.string.review_image_compression_max),
                        summary = str(
                            R.string.review_compression_max_summary,
                            AppConfig.reviewImageCompressionMaxKb,
                            AppConfig.DEFAULT_REVIEW_IMAGE_COMPRESSION_KB,
                            str(R.string.review_download_help_image_compression)
                        ),
                        min = AppConfig.MIN_REVIEW_COMPRESSION_KB,
                        max = 10240,
                        value = AppConfig.reviewImageCompressionMaxKb,
                        onSelected = { AppConfig.reviewImageCompressionMaxKb = it },
                        visible = booleanSetting(PreferKey.syncCacheReview, true) and
                            booleanSetting(PreferKey.compressReviewImages, true)
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_engine_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    choice(
                        key = cfg.ENGINE,
                        title = str(R.string.review_download_engine),
                        entriesRes = R.array.review_download_engine_entries,
                        valuesRes = R.array.review_download_engine_values,
                        defaultValue = "auto",
                        summary = str(R.string.review_download_engine_summary)
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_data_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    numberSpec(
                        ReviewDownloadConfig.Number.DATA,
                        str(R.string.review_download_help_data)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.PAGE_SIZE,
                        str(R.string.review_download_help_page_size)
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_replies_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    numberSpec(
                        ReviewDownloadConfig.Number.REPLIES,
                        str(R.string.review_download_help_replies)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.REPLY_SIZE,
                        str(R.string.review_download_help_reply_size)
                    ),
                    switch(
                        key = cfg.REUSE_REPLIES,
                        title = str(R.string.review_download_reuse_replies),
                        summary = str(R.string.review_download_help_reuse_replies),
                        defaultValue = true
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_pages_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    numberSpec(
                        ReviewDownloadConfig.Number.PAGES,
                        str(R.string.review_download_help_pages)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.STABLE_INTERVAL,
                        str(R.string.review_download_help_stable_interval)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.STABLE_ROUNDS,
                        str(R.string.review_download_help_stable_rounds)
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_resources_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    numberSpec(
                        ReviewDownloadConfig.Number.RESOURCES,
                        str(R.string.review_download_help_resources)
                    ),
                    switch(
                        key = cfg.REUSE_RESOURCES,
                        title = str(R.string.review_download_reuse_resources),
                        summary = str(R.string.review_download_help_reuse_resources),
                        defaultValue = true
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_heavy_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    numberSpec(
                        ReviewDownloadConfig.Number.HEAVY,
                        str(R.string.review_download_help_heavy)
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_timeout_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    numberSpec(
                        ReviewDownloadConfig.Number.PAGE_TIMEOUT,
                        str(R.string.review_download_help_page_timeout)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.DATA_TIMEOUT,
                        str(R.string.review_download_help_data_timeout)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.RESOURCE_TIMEOUT,
                        str(R.string.review_download_help_resource_timeout)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.RETRIES,
                        str(R.string.review_download_help_retries)
                    ),
                    numberSpec(
                        ReviewDownloadConfig.Number.RETRY_INTERVAL,
                        str(R.string.review_download_help_retry_interval)
                    ),
                    choice(
                        key = cfg.BACKOFF,
                        title = str(R.string.review_download_backoff),
                        entriesRes = R.array.review_download_backoff_entries,
                        valuesRes = R.array.review_download_backoff_values,
                        defaultValue = "fixed"
                    )
                )
            ),
            SettingSectionSpec(
                title = str(R.string.review_download_integrity_section),
                collapsible = true,
                collapsedByDefault = true,
                items = listOf(
                    switch(
                        key = cfg.REQUIRE_AVATARS,
                        title = str(R.string.review_download_require_avatars),
                        summary = str(R.string.review_download_requirement_summary),
                        defaultValue = true
                    ),
                    switch(
                        key = cfg.REQUIRE_IMAGES,
                        title = str(R.string.review_download_require_images),
                        summary = str(R.string.review_download_requirement_summary),
                        defaultValue = true
                    ),
                    switch(
                        key = cfg.REQUIRE_CSS,
                        title = str(R.string.review_download_require_css),
                        summary = str(R.string.review_download_requirement_summary),
                        defaultValue = true
                    ),
                    switch(
                        key = cfg.REQUIRE_FONTS,
                        title = str(R.string.review_download_require_fonts),
                        summary = str(
                            R.string.review_download_help_requirement_fonts,
                            str(R.string.review_download_requirement_summary)
                        ),
                        defaultValue = true
                    )
                )
            )
        )
    }
}
