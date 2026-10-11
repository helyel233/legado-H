package io.legado.app.ui.host

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import io.legado.app.R
import io.legado.app.ui.config.AiConfigFragment
import io.legado.app.ui.config.BackupConfigFragment
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.CoverConfigFragment
import io.legado.app.ui.config.DiscoveryConfigFragment
import io.legado.app.ui.config.DiscoverySubscriptionConfigFragment
import io.legado.app.ui.config.NetworkDnsConfigFragment
import io.legado.app.ui.config.OtherConfigFragment
import io.legado.app.ui.config.SubscriptionConfigFragment
import io.legado.app.ui.config.ThemeConfigFragment
import io.legado.app.ui.config.WelcomeConfigFragment
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec

/**
 * P5-1 设置页 V5 化：**新外框 + 旧内容**。
 *
 * 旧的 ConfigActivity 是一套自绘 Toolbar 壳 + 12 个设置分区 Fragment。本页不重做
 * 设置内容（几十上百个设置项，逐项重写风险大、收益低），只把外框换成 V5：
 *   背景层 + 玻璃顶栏（返回 + 分区标题 + 溢出菜单）+ 边缘到边缘
 * 内容仍是同一批 Fragment（它们本身已是 Compose 实现，颜色经 V5ThemeSync 同步）。
 *
 * **菜单桥**：旧 Fragment 通过 `requireActivity().addMenuProvider(...)` 注册菜单。
 * 本页覆写 addMenuProvider 把它们收集起来，再用一个 PopupMenu 让 provider 把菜单
 * 灌进原生 Menu（只用公开 API），取出条目后以 V5 溢出菜单呈现，点击回灌
 * `onMenuItemSelected` —— 旧 Fragment 一行代码都不用改。
 */
class HostSettingsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CONFIG_TAG = "configTag"
        private const val EXTRA_TARGET_KEY = "targetKey"

        fun start(context: Context, configTag: String, targetKey: String? = null) {
            context.startActivity(
                Intent(context, HostSettingsActivity::class.java).apply {
                    putExtra(EXTRA_CONFIG_TAG, configTag)
                    targetKey?.let { putExtra(EXTRA_TARGET_KEY, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        private fun fragmentFor(configTag: String): Class<out Fragment>? = when (configTag) {
            ConfigTag.OTHER_CONFIG -> OtherConfigFragment::class.java
            ConfigTag.NETWORK_DNS_CONFIG -> NetworkDnsConfigFragment::class.java
            ConfigTag.THEME_CONFIG -> ThemeConfigFragment::class.java
            ConfigTag.BACKUP_CONFIG -> BackupConfigFragment::class.java
            ConfigTag.AI_CONFIG -> AiConfigFragment::class.java
            ConfigTag.COVER_CONFIG -> CoverConfigFragment::class.java
            ConfigTag.WELCOME_CONFIG -> WelcomeConfigFragment::class.java
            ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG ->
                DiscoverySubscriptionConfigFragment::class.java
            ConfigTag.DISCOVERY_CONFIG -> DiscoveryConfigFragment::class.java
            ConfigTag.SUBSCRIPTION_CONFIG -> SubscriptionConfigFragment::class.java
            else -> null
        }
    }

    private var titleText by mutableStateOf("")
    internal val menuProviders = mutableStateListOf<MenuProvider>()
    internal var menuRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        val configTag = intent.getStringExtra(EXTRA_CONFIG_TAG) ?: ConfigTag.OTHER_CONFIG
        val fragmentClass = fragmentFor(configTag)
        if (fragmentClass == null) {
            finish()
            return
        }
        titleText = getString(R.string.setting)

        setContentView(
            ComposeView(this).apply {
                setContent {
                    HostV5ThemeRoot {
                        HostSettingsScreen(
                            configTag = configTag,
                            fragmentClass = fragmentClass,
                            title = titleText,
                        )
                    }
                }
            }
        )
    }

    // ---------------------------------------------------------------- 菜单桥

    override fun addMenuProvider(provider: MenuProvider) {
        super.addMenuProvider(provider)
        menuProviders.add(provider)
        menuRevision++
    }

    override fun addMenuProvider(provider: MenuProvider, owner: androidx.lifecycle.LifecycleOwner) {
        super.addMenuProvider(provider, owner)
        menuProviders.add(provider)
        menuRevision++
    }

    override fun addMenuProvider(
        provider: MenuProvider,
        owner: androidx.lifecycle.LifecycleOwner,
        state: Lifecycle.State
    ) {
        super.addMenuProvider(provider, owner, state)
        menuProviders.add(provider)
        menuRevision++
    }

    override fun removeMenuProvider(provider: MenuProvider) {
        super.removeMenuProvider(provider)
        menuProviders.remove(provider)
        menuRevision++
    }

    /** 用 PopupMenu 让 provider 灌菜单，取出条目（只用公开 API）。 */
    internal fun menuItems(): List<MenuItem> {
        if (menuProviders.isEmpty()) return emptyList()
        val popup = PopupMenu(this, window.decorView)
        menuProviders.forEach { provider ->
            runCatching { provider.onCreateMenu(popup.menu, menuInflater) }
        }
        val items = (0 until popup.menu.size()).map { popup.menu.getItem(it) }
        return items.filter { it.isVisible && it.isEnabled }
    }

    internal fun onMenuClick(item: MenuItem) {
        menuProviders.forEach { provider ->
            runCatching { if (provider.onMenuItemSelected(item)) return }
        }
    }

    // Fragment 通过 activity.setTitle 设置分区标题（与旧 ConfigActivity 同口径）
    override fun setTitle(resId: Int) {
        super.setTitle(resId)
        runCatching { titleText = getString(resId) }
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        titleText = title?.toString().orEmpty()
    }
}

@Composable
private fun HostSettingsScreen(
    configTag: String,
    fragmentClass: Class<out Fragment>,
    title: String,
) {
    val context = LocalContext.current
    val activity = context as HostSettingsActivity
    val colors = LocalSemanticColors.current
    val visual = LocalVisualSpec.current
    var menuOpen by remember { mutableStateOf(false) }

    HostV5Page(
        title = title.ifBlank { "设置" },
        subtitle = null,
        onBack = { activity.finish() },
        trailing = {
            if (activity.menuProviders.isNotEmpty()) {
                Text(
                    text = "\u22EE",
                    style = visual.type.title,
                    color = colors.primary,
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) { menuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FragmentContainerView(ctx).apply { id = R.id.host_settings_container }
            },
        )
    }

    // 容器就绪后挂 Fragment（与旧 ConfigActivity 的 replaceFragment 等价）
    LaunchedEffect(configTag) {
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.host_settings_container, fragmentClass.newInstance(), configTag)
            .commit()
    }

    if (menuOpen) {
        // provider 增删会自增 menuRevision，据此重建条目
        val items = remember(activity.menuRevision) { activity.menuItems() }
        AppMenuSheet(
            title = title.ifBlank { "设置" },
            actions = items.map { item ->
                item.title?.toString().orEmpty() to { activity.onMenuClick(item) }
            },
            onDismiss = { menuOpen = false },
        )
    }
}
