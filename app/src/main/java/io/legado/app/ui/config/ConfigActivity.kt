package io.legado.app.ui.config

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout.LayoutParams as FrameLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.utils.dpToPx
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import androidx.viewbinding.ViewBinding

private const val CONFIG_MENU_HOST_WIDTH_DP = 128

class ConfigActivity : VMBaseActivity<ViewBinding, ConfigViewModel>() {

    private lateinit var titleComposeView: ComposeView
    private lateinit var menuToolbar: Toolbar
    private var titleText by mutableStateOf("")
    private var parentPathText by mutableStateOf<String?>(null)

    override val binding: ViewBinding by lazy {
        titleText = getString(R.string.setting)
        titleComposeView = ComposeView(this)
        menuToolbar = Toolbar(this).apply {
            title = ""
            subtitle = ""
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setContentInsetsAbsolute(0, 0)
            contentInsetStartWithNavigation = 0
            contentInsetEndWithActions = 0
        }
        val topBarHost = FrameLayout(this).apply {
            addView(
                titleComposeView,
                FrameLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                menuToolbar,
                FrameLayoutParams(
                    CONFIG_MENU_HOST_WIDTH_DP.dpToPx(),
                    56.dpToPx(),
                    Gravity.END or Gravity.BOTTOM
                )
            )
            setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
                val top = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                (menuToolbar.layoutParams as? FrameLayoutParams)?.let { params ->
                    if (params.topMargin != top) {
                        params.topMargin = top
                        menuToolbar.layoutParams = params
                    }
                }
                windowInsets
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                topBarHost,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                FrameLayout(this@ConfigActivity).apply {
                    id = R.id.configFrameLayout
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        object : ViewBinding {
            override fun getRoot(): View = root
        }
    }
    override val viewModel by viewModels<ConfigViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        setSupportActionBar(menuToolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        titleComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        titleComposeView.setContent {
            ConfigTopBar(
                title = titleText,
                parentPath = parentPathText,
                onBack = ::supportFinishAfterTransition
            )
        }
        val configTag = intent.getStringExtra("configTag")
        parentPathText = breadcrumbOf(configTag)
        when (configTag) {
            ConfigTag.READING_CONFIG -> replaceFragment(configTag, ReadingConfigFragment::class.java)
            ConfigTag.NETWORK_CONFIG -> replaceFragment(configTag, NetworkConfigFragment::class.java)
            ConfigTag.STORAGE_CONFIG -> replaceFragment(configTag, StorageConfigFragment::class.java)
            ConfigTag.ADVANCED_CONFIG -> replaceFragment(configTag, AdvancedConfigFragment::class.java)
            ConfigTag.NETWORK_DNS_CONFIG -> replaceFragment(configTag, NetworkDnsConfigFragment::class.java)
            ConfigTag.THEME_CONFIG -> replaceFragment(configTag, ThemeConfigFragment::class.java)
            ConfigTag.BACKUP_CONFIG -> replaceFragment(configTag, BackupConfigFragment::class.java)
            ConfigTag.AI_CONFIG -> replaceFragment(configTag, AiConfigFragment::class.java)
            ConfigTag.COVER_CONFIG -> replaceFragment(configTag, CoverConfigFragment::class.java)
            ConfigTag.WELCOME_CONFIG -> replaceFragment(configTag, WelcomeConfigFragment::class.java)
            ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG ->
                replaceFragment(configTag, DiscoverySubscriptionConfigFragment::class.java)
            ConfigTag.COMMENT_CONFIG -> replaceFragment(configTag, CommentConfigFragment::class.java)
            ConfigTag.BOOKSHELF_CONFIG -> replaceFragment(configTag, BookshelfSettingsFragment::class.java)
            ConfigTag.AUDIO_CONFIG -> replaceFragment(configTag, AudioConfigFragment::class.java)
            ConfigTag.MANGA_VIDEO_CONFIG ->
                replaceFragment(configTag, MangaVideoConfigFragment::class.java)
    ConfigTag.PAGE_LAYOUT_CONFIG ->
        replaceFragment(configTag, PageLayoutConfigFragment::class.java)
    ConfigTag.EXPLORE_GLASS_CONFIG ->
        replaceFragment(configTag, ExploreGlassConfigFragment::class.java)
    else -> finish()
        }
    }

    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        titleText = getString(resId)
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        titleText = title?.toString().orEmpty()
    }

    /**
     * 顶栏副标题显示「这一页是从哪个一级分类进来的」，是设置页父子体系的可见线索。
     * 只列父级、不含页面自身（自身已经是标题），避免重复。
     * 新增 ConfigTag 时记得在这里补一条，否则该页顶栏会没有路径提示。
     *
     * 这里的父级名必须与 [MyFragment.buildSections] 里的分区标题**同源**：
     * 用的是同一批 string 资源，而不是另起一套名字。改分区名时若漏改这里，
     * 顶栏会出现「阅读」而页面实际挂在「阅读与界面」下的割裂感。
     */
    private fun breadcrumbOf(configTag: String?): String? {
        val parents = when (configTag) {
            ConfigTag.READING_CONFIG -> listOf(R.string.config_category_reading_ui)
            ConfigTag.NETWORK_CONFIG -> listOf(R.string.config_category_network_sync)
            ConfigTag.STORAGE_CONFIG -> listOf(R.string.config_category_data_tools)
            ConfigTag.ADVANCED_CONFIG -> listOf(R.string.config_category_advanced)
            ConfigTag.NETWORK_DNS_CONFIG ->
                listOf(R.string.config_category_network_sync, R.string.network_setting)

            // 主题模式已上提到一级行，界面设置/封面/欢迎页都归属「阅读与界面」
            ConfigTag.THEME_CONFIG,
            ConfigTag.COVER_CONFIG,
            ConfigTag.WELCOME_CONFIG -> listOf(R.string.config_category_reading_ui)

            ConfigTag.AI_CONFIG -> listOf(R.string.config_category_ai_automation)
            ConfigTag.BACKUP_CONFIG -> listOf(R.string.config_category_network_sync)
            // 发现/订阅页设置已上提为「内容与规则」下的一级行，不再是界面设置的下一层
            ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG ->
                listOf(R.string.config_category_content)

            // 发现/订阅/首页三处布局归拢为「内容与规则」下的独立页
            ConfigTag.PAGE_LAYOUT_CONFIG ->
                listOf(R.string.config_category_content)

            // 发现页毛玻璃与发现/订阅设置、页面布局同属「内容与规则」域
            ConfigTag.EXPLORE_GLASS_CONFIG ->
                listOf(R.string.config_category_content)

            // 评论抓取已从高级设置拆出，作为「高级与关于」下的独立二级页
            ConfigTag.COMMENT_CONFIG -> listOf(R.string.config_category_advanced)

            // 书架设置从阅读设置拆出，归属「阅读与界面」
            ConfigTag.BOOKSHELF_CONFIG -> listOf(R.string.config_category_reading_ui)

            // 朗读与音频、漫画与视频同属「读起来是什么样」，都挂在「阅读与界面」下
            ConfigTag.AUDIO_CONFIG,
            ConfigTag.MANGA_VIDEO_CONFIG -> listOf(R.string.config_category_reading_ui)

            else -> return null
        }
        return parents.joinToString(" › ") { getString(it) }
    }

    fun <T : Fragment> replaceFragment(configTag: String, fragmentClass: Class<T>) {
        intent.putExtra("configTag", configTag)
        val configFragment = supportFragmentManager.findFragmentByTag(configTag)
            ?: fragmentClass.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.configFrameLayout, configFragment, configTag)
            .commit()
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.RECREATE) {
            recreate()
        }
    }

}

@Composable
private fun ConfigTopBar(
    title: String,
    parentPath: String?,
    onBack: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    val hasPath = !parentPath.isNullOrBlank()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.bodyFontFamily)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(if (hasPath) 64.dp else 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = palette.primaryText
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.primaryText,
                    fontFamily = palette.titleFontFamily,
                    fontSize = if (hasPath) 18.sp else 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (hasPath) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = parentPath.orEmpty(),
                        color = palette.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(CONFIG_MENU_HOST_WIDTH_DP.dp))
        }
    }
}
