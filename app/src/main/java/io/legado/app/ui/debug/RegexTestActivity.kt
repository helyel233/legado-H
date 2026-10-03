package io.legado.app.ui.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityRegexTestBinding
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 正则测试（移植自 Legado_Max）
 *
 * 测试替换规则的正则匹配效果：实时预览匹配位置、分组信息与替换结果。
 * 从替换规则编辑页右上角菜单进入时，自动带入当前规则的匹配串与替换串。
 */
class RegexTestActivity : BaseActivity<ActivityRegexTestBinding>() {

    companion object {
        fun startIntent(
            context: Context,
            pattern: String = "",
            replacement: String = "",
            isRegex: Boolean = true
        ): Intent {
            return Intent(context, RegexTestActivity::class.java).apply {
                putExtra("pattern", pattern)
                putExtra("replacement", replacement)
                putExtra("isRegex", isRegex)
            }
        }
    }

    override val binding by viewBinding(ActivityRegexTestBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val pattern = intent.getStringExtra("pattern").orEmpty()
        val replacement = intent.getStringExtra("replacement").orEmpty()
        val isRegex = intent.getBooleanExtra("isRegex", true)
        binding.composeView.setContent {
            LegadoComposeTheme {
                RegexTestScreen(
                    initialPattern = pattern,
                    initialReplacement = replacement,
                    initialIsRegex = isRegex
                )
            }
        }
    }
}
