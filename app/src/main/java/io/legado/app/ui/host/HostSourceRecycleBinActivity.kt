package io.legado.app.ui.host

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.source.recycle.SourceRecycleBinScreen
import io.legado.app.ui.widget.compose.LegadoComposeTheme

/**
 * 规则回收站（V5 换壳）。
 *
 * 内容 UI 本就是 Compose（[SourceRecycleBinScreen]，内含分类 chips/搜索/列表/批量操作），
 * 旧 Activity 只是把它塞进 XML 的 ComposeView。这里改为 V5 壳：
 * 顶栏与背景层交给 [HostV5Page]，内容仍走其自带主题（内部样式已桥接 V5 形状色）。
 */
class HostSourceRecycleBinActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { HostSourceRecycleBinScreen() }
    }
}

@Composable
private fun HostSourceRecycleBinScreen() {
    val activity = LocalContext.current as AppCompatActivity
    HostV5Page(
        title = stringResource(R.string.source_recycle_bin),
        onBack = { activity.finish() },
    ) {
        LegadoComposeTheme {
            SourceRecycleBinScreen()
        }
    }
}
