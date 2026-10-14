// __P1__

package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.host.HostV5Page
import io.legado.app.ui.host.setV5Content
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppMenuSheet
import io.legado.app.uikit.v5.components.AppOverflowMenuButton
import io.legado.app.uikit.v5.components.AppRowWithSwitch
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读菜单自定义按钮编辑 V5 化。
 * 旧版 BaseActivity + ActivityParagraphRuleEditBinding + optionsMenu，替换为
 * setV5Content + HostV5Page + Compose 表单。业务逻辑（DAO/剪贴板/校验/事件）逐行保留。
 */
class ReadMenuCustomButtonEditActivity : AppCompatActivity() {

    internal var meta by mutableStateOf(ReadMenuCustomButton())
    internal var name by mutableStateOf("")
    internal var loginUrl by mutableStateOf("")
    internal var loginUi by mutableStateOf("")
    internal var timeout by mutableStateOf("")
    internal var script by mutableStateOf("")
    internal var jsLib by mutableStateOf("")
    internal var enabledCookieJar by mutableStateOf(false)
    internal var loading by mutableStateOf(true)
    internal var focusedTag by mutableStateOf("script")

    internal var overflowMenuOpen by mutableStateOf(false)

    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra("text")?.let { text ->
                    applyFocusedEdit(text)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setV5Content { ReadMenuCustomButtonEditContent(this@ReadMenuCustomButtonEditActivity) }
        val id = intent.getLongExtra("id", 0L)
        lifecycleScope.launch {
            meta = withContext(Dispatchers.IO) {
                appDb.readMenuCustomButtonDao.get(id)
            } ?: ReadMenuCustomButton()
            bindButton()
        }
    }

    private fun bindButton() {
        name = meta.name
        loginUrl = meta.loginUrl
        loginUi = meta.loginUi
        enabledCookieJar = meta.enabledCookieJar
        timeout = meta.validTimeout().toString()
        script = meta.script
        jsLib = meta.jsLib
        loading = false
    }

    internal fun applyFocusedEdit(text: String) {
        when (focusedTag) {
            "name" -> name = text
            "loginUrl" -> loginUrl = text
            "loginUi" -> loginUi = text
            "timeout" -> timeout = text
            "jsLib" -> jsLib = text
            else -> script = text
        }
    }

    internal fun onFullEditClicked() {
        if (loading) {
            toastOnUi(R.string.data_loading)
            return
        }
        textEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", focusedText())
            putExtra("title", hintFor(focusedTag))
        })
    }

    private fun focusedText(): String = when (focusedTag) {
        "name" -> name
        "loginUrl" -> loginUrl
        "loginUi" -> loginUi
        "timeout" -> timeout
        "jsLib" -> jsLib
        else -> script
    }

    private fun hintFor(tag: String): String? = when (tag) {
        "name" -> getString(R.string.name)
        "loginUrl" -> getString(R.string.login_url)
        "loginUi" -> getString(R.string.login_ui)
        "timeout" -> getString(R.string.timeout_millisecond)
        "script" -> getString(R.string.read_menu_button_script)
        "jsLib" -> "jsLib"
        else -> null
    }

    internal fun pasteButton() {
        val raw = getClipText()
        val imported = GSON.fromJsonObject<ReadMenuCustomButton>(raw).getOrNull()
        if (imported == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        meta = imported.copy(id = meta.id, order = meta.order, updateTime = System.currentTimeMillis())
        bindButton()
    }

    internal fun getButton(): ReadMenuCustomButton = meta.copy(
        name = name.trim(),
        loginUrl = loginUrl,
        loginUi = loginUi,
        enabledCookieJar = enabledCookieJar,
        timeoutMillisecond = timeout.toLongOrNull() ?: 3000L,
        script = script,
        jsLib = jsLib,
        updateTime = System.currentTimeMillis()
    )

    internal fun save() {
        if (loading) {
            toastOnUi(R.string.data_loading)
            return
        }
        val edited = getButton()
        if (edited.name.isBlank() || edited.script.isBlank()) {
            toastOnUi(R.string.read_menu_custom_button_save_invalid)
            return
        }
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                if (edited.id == 0L) {
                    val order = (appDb.readMenuCustomButtonDao.maxOrder() ?: 0) + 1
                    appDb.readMenuCustomButtonDao.insert(edited.copy(order = order))
                } else {
                    appDb.readMenuCustomButtonDao.update(edited)
                    edited.id
                }
            }
            postEvent(EventBus.READ_MENU_BUTTON_CHANGED, true)
            setResult(Activity.RESULT_OK, Intent().putExtra("id", id))
            finish()
        }
    }
}

@Composable
private fun ReadMenuCustomButtonEditContent(activity: ReadMenuCustomButtonEditActivity) {
    val density = LocalVisualSpec.current.density
    val enabled = !activity.loading

    HostV5Page(
        title = stringResource(R.string.read_menu_custom_button_edit),
        onBack = { activity.finish() },
        trailing = {
            AppFilledButton(
                text = stringResource(R.string.action_save),
                enabled = enabled,
                onClick = { activity.save() },
            )
            AppOverflowMenuButton(
                contentDescription = stringResource(R.string.more),
                onClick = { activity.overflowMenuOpen = true },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(density.screenPadding),
            verticalArrangement = Arrangement.spacedBy(density.blockGap / 2),
        ) {
            fun tag(tagName: String) = Modifier.onFocusChanged {
                if (it.isFocused) activity.focusedTag = tagName
            }
            AppTextField(
                value = activity.name,
                onValueChange = { activity.name = it },
                modifier = tag("name"),
                placeholder = stringResource(R.string.name),
                enabled = enabled,
                singleLine = true,
            )
            AppTextField(
                value = activity.loginUrl,
                onValueChange = { activity.loginUrl = it },
                modifier = tag("loginUrl"),
                placeholder = stringResource(R.string.login_url),
                enabled = enabled,
                singleLine = false,
            )
            AppTextField(
                value = activity.loginUi,
                onValueChange = { activity.loginUi = it },
                modifier = tag("loginUi"),
                placeholder = stringResource(R.string.login_ui),
                enabled = enabled,
                singleLine = false,
            )
            AppRowWithSwitch(
                title = "启用 Cookie",
                checked = activity.enabledCookieJar,
                onCheckedChange = { activity.enabledCookieJar = it },
                card = false,
                enabled = enabled,
            )
            AppTextField(
                value = activity.timeout,
                onValueChange = { activity.timeout = it },
                modifier = tag("timeout"),
                placeholder = stringResource(R.string.timeout_millisecond),
                enabled = enabled,
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.read_menu_button_script),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = activity.script,
                onValueChange = { activity.script = it },
                modifier = tag("script"),
                placeholder = stringResource(R.string.read_menu_button_script),
                enabled = enabled,
                singleLine = false,
            )
            Text(
                text = "jsLib",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AppTextField(
                value = activity.jsLib,
                onValueChange = { activity.jsLib = it },
                modifier = tag("jsLib"),
                placeholder = "jsLib",
                enabled = enabled,
                singleLine = false,
            )
        }
    }

    if (activity.overflowMenuOpen) {
        AppMenuSheet(
            title = stringResource(R.string.read_menu_custom_button_edit),
            actions = listOf(
                stringResource(R.string.edit_content) to {
                    activity.overflowMenuOpen = false
                    activity.onFullEditClicked()
                },
                stringResource(R.string.copy_rule) to {
                    activity.overflowMenuOpen = false
                    activity.sendToClip(GSON.toJson(activity.getButton()))
                },
                stringResource(R.string.paste_rule) to {
                    activity.overflowMenuOpen = false
                    activity.pasteButton()
                },
                stringResource(R.string.help) to {
                    activity.overflowMenuOpen = false
                    activity.showHelp("readMenuCustomButtonHelp")
                },
            ),
            onDismiss = { activity.overflowMenuOpen = false },
        )
    }
}
