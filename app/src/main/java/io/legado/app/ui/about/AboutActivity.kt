package io.legado.app.ui.about

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAboutBinding
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.ui.glass.applyWallpaperGlassBackground
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.viewbindingdelegate.viewBinding


class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    override val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        applyAboutCardBackground()
        val fTag = "aboutFragment"
        var aboutFragment = supportFragmentManager.findFragmentByTag(fTag)
        if (aboutFragment == null) aboutFragment = AboutFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_fragment, aboutFragment, fTag)
            .commit()
        binding.tvAppSummary.post {
            kotlin.runCatching {
                val span = ForegroundColorSpan(accentColor)
                val spannableString = SpannableString(binding.tvAppSummary.text)
                val gzh = getString(R.string.legado_gzh)
                val start = spannableString.indexOf(gzh)
                spannableString.setSpan(
                    span, start, start + gzh.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                binding.tvAppSummary.text = spannableString
            }
        }
    }

    /**
     * 关于页信息卡的背景：壁纸可见时壁纸域玻璃，否则保持原来的实底圆角卡。
     *
     * 原先这里无条件铺 `UiCorner.opaqueRounded(cardColor)` 的**不透明**卡面，壁纸下
     * 就是一块实心板，与其余页面卡片割裂。圆角仍取 `UiCorner.panelRadius`（与
     * `shape_card_view.xml` 的 `ui_panel_radius` 同源、同样跟随 `uiCornerScale`），
     * 文字色不动，solid 档 / 墨水屏 / 低内存 / 无壁纸由
     * [io.legado.app.ui.glass.GlassSurface.pagePanelBackground] 逐值回退到原实底。
     */
    private fun applyAboutCardBackground() {
        val radius = UiCorner.panelRadius(this)
        val fillColor = themeCardColorOrDefault()
        binding.llAbout.applyWallpaperGlassBackground(radius) {
            UiCorner.opaqueRounded(fillColor, radius)
        }
    }

    override fun onResume() {
        super.onResume()
        // 常驻信息卡的生命周期重判：夜间翻转 / 玻璃档位变化等不 recreate 的路径
        // 也要让卡面跟随（底层管线按签名去重，无变化时是零开销）
        applyAboutCardBackground()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.about, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_scoring -> openUrl("market://details?id=$packageName")
            R.id.menu_share_it -> share(
                getString(R.string.app_share_description_sigma),
                getString(R.string.app_name)
            )
        }
        return super.onCompatOptionsItemSelected(item)
    }

}
