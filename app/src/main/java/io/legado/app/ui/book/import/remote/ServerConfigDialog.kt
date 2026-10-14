package io.legado.app.ui.book.import.remote

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.data.entities.Server
import io.legado.app.ui.host.V5DialogFragment
import io.legado.app.uikit.v5.components.AppDialog
import io.legado.app.uikit.v5.components.AppFilledButton
import io.legado.app.uikit.v5.components.AppGhostButton
import io.legado.app.uikit.v5.components.AppTextField
import io.legado.app.uikit.v5.runtime.LocalSemanticColors
import io.legado.app.uikit.v5.runtime.LocalVisualSpec
import io.legado.app.utils.GSON

/**
 * WebDAV 服务配置弹窗（V5 基座版）。
 *
 * 尺寸由旧 `AppDialogSize.Form` 映射为 widthFraction 0.94f / maxWidthDp 660；
 * 内容由旧 AppDialogFrame + LegadoComposeTheme 包裹 + 自定义 OutlinedTextField(label) 换为
 * V5 AppDialog + AppTextField（label 降级为 placeholder；密码字段走 `PasswordVisualTransformation`，
 * 由 AppTextField 的 visualTransformation 参数支撑）。
 * 「首次 LaunchedEffect 里异步读取已有配置回填」「save 成功后关闭」等语义不变。
 */
internal class ServerConfigDialog() : V5DialogFragment() {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    /** 旧 AppDialogSize.Form(widthFraction = 0.94f, maxWidthDp = 660) */
    override val widthFraction: Float = 0.94f
    override val maxWidthDp: Int = 660

    private val viewModel by viewModels<ServerConfigViewModel>()

    @Composable
    override fun Content() {
        val visual = LocalVisualSpec.current
        val colors = LocalSemanticColors.current
        val density = visual.density
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            viewModel.init(arguments?.getLong("id")) {
                val server = viewModel.mServer
                name = server?.name.orEmpty()
                val config = server?.getConfigJsonObject()
                url = config?.optString("url").orEmpty()
                username = config?.optString("username").orEmpty()
                password = config?.optString("password").orEmpty()
            }
        }

        AppDialog(onDismiss = { dismiss() }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(density.blockGap / 2)
            ) {
                Text(
                    text = stringResource(R.string.web_dav_set),
                    style = visual.type.title,
                    color = colors.onSurface
                )
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.name),
                    singleLine = true,
                )
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "url",
                    singleLine = true,
                )
                AppTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "username",
                    singleLine = true,
                )
                AppTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "password",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(density.blockGap / 2)) {
                    AppGhostButton(
                        text = stringResource(R.string.cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { dismiss() },
                    )
                    AppFilledButton(
                        text = stringResource(R.string.ok),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.save(buildServer(name, url, username, password)) {
                                dismissAllowingStateLoss()
                            }
                        },
                    )
                }
            }
        }
    }

    private fun buildServer(
        name: String,
        url: String,
        username: String,
        password: String
    ): Server {
        val server = viewModel.mServer?.copy() ?: Server()
        server.name = name
        server.type = Server.TYPE.WEBDAV
        server.config = GSON.toJson(
            hashMapOf("url" to url, "username" to username, "password" to password)
        )
        return server
    }
}
