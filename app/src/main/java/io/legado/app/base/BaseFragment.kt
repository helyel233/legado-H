package io.legado.app.base

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.applyTint

@Suppress("MemberVisibilityCanBePrivate")
abstract class BaseFragment(@LayoutRes layoutID: Int) : Fragment(layoutID) {

    var supportToolbar: Toolbar? = null
        private set

    val menuInflater: MenuInflater
        @SuppressLint("RestrictedApi")
        get() = SupportMenuInflater(requireContext())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyRootBackgroundPolicy(view)
        applyTitleBarWallpaperGlass(view)
        onMultiWindowModeChanged()
        observeLiveBus()
        onFragmentCreated(view, savedInstanceState)
    }

    private fun applyRootBackgroundPolicy(view: View) {
        if (!AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(requireContext())) {
            ViewCompat.setBackgroundTintList(view, null)
            view.background = null
        }
    }

    /**
     * 让本 Fragment 布局里的 `@id/title_bar` 自动接上壁纸域玻璃（与
     * [BaseActivity] 同一口径、同一份判定）。
     *
     * 主界面四个经典顶栏页（首页 / 我的 / 书架样式 2 / 阅读记录）都走这条路径：
     * 顶栏在 Fragment 布局里、`attachToActivity="false"`，采样的是壁纸位图，
     * 与发现 / 订阅 / 书架样式 1 的 `MainTopBarView` 覆盖式采样是两种合法形态
     * （那三处的列表会滚到顶栏下面，必须采兄弟子树；这几处的顶栏在内容上方，
     * 背后就是壁纸）。
     *
     * Fragment 的「是否露出壁纸」取宿主 Activity 的
     * [BaseActivity.showsWallpaperBehindContent]——这正是需求里要求的
     * 「我的页顶栏必须与下方 GlassPanel 用同一个 wallpaperVisible 判定」。
     */
    private fun applyTitleBarWallpaperGlass(view: View) {
        val pageShowsWallpaper = (activity as? BaseActivity<*>)?.showsWallpaperBehindContent
            ?: (!AppConfig.isEInkMode && ThemeConfig.hasUsableBgImage(requireContext()))
        view.findViewById<TitleBar>(R.id.title_bar)
            ?.applyAutoWallpaperGlass(pageShowsWallpaper)
    }

    abstract fun onFragmentCreated(view: View, savedInstanceState: Bundle?)

    /**
     * 回到前台时按同一口径重判顶栏玻璃。
     *
     * 玻璃档位 / 强度 / 描边色 / 夜间模式 / 壁纸都可能在别的页面改过，而 Fragment 的
     * 视图（连同顶栏背景）是常驻的，不重判就会停在旧材质上——主界面四个标签页
     * （首页 / 我的 / 书架 / 订阅）之间来回切最常撞到这条路径。
     * [TitleBar.applyWallpaperGlass] 按签名幂等，未变化时零开销。
     */
    override fun onResume() {
        super.onResume()
        view?.let { applyTitleBarWallpaperGlass(it) }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        onMultiWindowModeChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onMultiWindowModeChanged()
    }

    private fun onMultiWindowModeChanged() {
        (activity as? BaseActivity<*>)?.let {
            view?.findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(it.isInMultiWindow, it.fullScreen)
        }
    }

    fun setSupportToolbar(toolbar: Toolbar) {
        supportToolbar = toolbar
        supportToolbar?.let {
            it.menu.apply {
                onCompatCreateOptionsMenu(this)
                applyTint(requireContext())
            }

            it.setOnMenuItemClickListener { item ->
                onCompatOptionsItemSelected(item)
                true
            }
        }
    }

    open fun observeLiveBus() {
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) {
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) {
    }

}
