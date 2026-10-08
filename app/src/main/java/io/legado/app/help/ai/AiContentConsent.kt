package io.legado.app.help.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 书籍正文外发授权闸门。
 *
 * 阅读页问 AI、AI 章节总结、AI 多角色朗读等能力会把章节正文发送给用户配置的第三方
 * AI 服务商处理，正文会离开本机。首次使用时弹窗告知并请求授权；授权决策为
 * ask（每次询问，默认）/ allow（始终允许）/ deny（始终禁止），可在「AI 设置 - 正文隐私授权」修改。
 */
object AiContentConsent {

    fun isAllowed(): Boolean {
        return AppConfig.aiExternalContentConsent == AppConfig.AI_CONTENT_CONSENT_ALLOW
    }

    fun isDenied(): Boolean {
        return AppConfig.aiExternalContentConsent == AppConfig.AI_CONTENT_CONSENT_DENY
    }

    /**
     * 带授权执行 [onAllowed]：
     * - 始终允许（或本次已允许）→ 直接执行
     * - 始终禁止 → toast 提示并回调 [onDenied]
     * - 每次询问 → 弹窗，用户选择后执行
     * 无法定位 Activity（非 UI 场景）时按禁止处理。
     */
    fun withConsent(
        context: Context,
        feature: String,
        onDenied: (() -> Unit)? = null,
        onAllowed: () -> Unit
    ) {
        when {
            isAllowed() -> onAllowed()
            isDenied() -> {
                context.toastOnUi("已在 AI 设置中禁止发送书籍正文，无法使用${feature}")
                onDenied?.invoke()
            }
            else -> {
                val activity = context.findActivity()
                if (activity == null) {
                    context.toastOnUi("「${feature}」需要发送书籍正文，请在 AI 设置中授权后使用")
                    onDenied?.invoke()
                    return
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.ai_content_consent_title)
                    .setMessage(activity.getString(R.string.ai_content_consent_message, feature))
                    .setPositiveButton(R.string.ai_content_consent_allow_once) { _, _ ->
                        onAllowed()
                    }
                    .setNeutralButton(R.string.ai_content_consent_allow_always) { _, _ ->
                        AppConfig.aiExternalContentConsent = AppConfig.AI_CONTENT_CONSENT_ALLOW
                        onAllowed()
                    }
                    .setNegativeButton(R.string.ai_content_consent_deny_once) { _, _ ->
                        context.toastOnUi("已取消，可在 AI 设置中修改授权")
                        onDenied?.invoke()
                    }
                    .show()
            }
        }
    }

    /**
     * 挂起式授权，用于协程内已排除缓存命中等场景后再请求授权。
     * 返回 true 表示用户同意发送正文。弹窗在主线程展示。
     */
    suspend fun awaitConsent(context: Context, feature: String): Boolean {
        if (isAllowed()) return true
        if (isDenied()) {
            context.toastOnUi("已在 AI 设置中禁止发送书籍正文，无法使用${feature}")
            return false
        }
        val activity = context.findActivity() ?: run {
            context.toastOnUi("「${feature}」需要发送书籍正文，请在 AI 设置中授权后使用")
            return false
        }
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var resumed = false
                fun resume(value: Boolean) {
                    if (!resumed) {
                        resumed = true
                        continuation.resume(value)
                    }
                }
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.ai_content_consent_title)
                    .setMessage(activity.getString(R.string.ai_content_consent_message, feature))
                    .setPositiveButton(R.string.ai_content_consent_allow_once) { _, _ ->
                        resume(true)
                    }
                    .setNeutralButton(R.string.ai_content_consent_allow_always) { _, _ ->
                        AppConfig.aiExternalContentConsent = AppConfig.AI_CONTENT_CONSENT_ALLOW
                        resume(true)
                    }
                    .setNegativeButton(R.string.ai_content_consent_deny_once) { _, _ ->
                        activity.toastOnUi("已取消，可在 AI 设置中修改授权")
                        resume(false)
                    }
                    .setOnCancelListener { resume(false) }
                    .show()
                continuation.invokeOnCancellation { runCatching { dialog.dismiss() } }
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
