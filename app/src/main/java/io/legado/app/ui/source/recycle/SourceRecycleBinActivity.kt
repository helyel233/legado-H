package io.legado.app.ui.source.recycle

import android.os.Bundle
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivitySourceRecycleBinBinding
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 规则回收站（移植自 Legado_Max）
 */
class SourceRecycleBinActivity :
    BaseActivity<ActivitySourceRecycleBinBinding>() {

    override val binding by viewBinding(ActivitySourceRecycleBinBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeView.setContent {
            LegadoComposeTheme {
                SourceRecycleBinScreen()
            }
        }
    }
}
