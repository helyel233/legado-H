package io.legado.app.ui.main

import android.os.Bundle
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityMainPageBinding
import io.legado.app.ui.main.homepage.HomepageFragment
import io.legado.app.ui.main.rss.RssFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * P1-b: standalone host for main-frame pages that lost their tab
 * (RSS sources, homepage aggregate). The hosted fragments bring their own
 * title bar; no extra chrome is added here.
 */
class MainPageActivity : BaseActivity<ActivityMainPageBinding>() {

    override val binding by viewBinding(ActivityMainPageBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (supportFragmentManager.findFragmentById(R.id.fl_fragment) != null) {
            return
        }
        val page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_RSS
        val fragment = when (page) {
            PAGE_HOMEPAGE -> HomepageFragment()
            else -> RssFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_fragment, fragment, page)
            .commit()
    }

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_RSS = "rss"
        const val PAGE_HOMEPAGE = "homepage"
    }
}
