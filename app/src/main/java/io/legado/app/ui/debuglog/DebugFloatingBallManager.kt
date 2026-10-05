package io.legado.app.ui.debuglog

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.about.DebugLogDialog
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.showDialogFragment
import splitties.init.appCtx

/**
 * 调试悬浮球管理器（移植自辞晨版 legados）。
 *
 * 把悬浮球 ComposeView 挂到当前 Activity 的 decorView 上（非系统窗口，无需悬浮窗权限），
 * 随 Activity 生命周期自动显隐；点击打开 [DebugLogDialog]，面板关闭后自动恢复。
 * 开关为 [AppConfig.debugLogFloatingBall]（默认关），在「关于 → 调试日志」区块切换。
 */
object DebugFloatingBallManager {

    private var isShowing = false
    private var isAttaching = false
    private var currentActivity: Activity? = null
    private var floatingBallView: ComposeView? = null
    private var showToken = 0
    private var registered = false

    /** App 启动时调用一次；开关关闭时回调空转，开销可忽略。 */
    fun init() {
        if (registered) return
        registered = true
        (appCtx as Application).registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (AppConfig.debugLogFloatingBall && !isShowing && !isAttaching) {
                        show(activity)
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    if (currentActivity === activity) {
                        hide()
                        currentActivity = null
                    }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (currentActivity === activity) {
                        showToken++
                        hide()
                        currentActivity = null
                    }
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
                    Unit
            }
        )
    }

    /** 设置开关变化时立即生效。 */
    fun updateFloatingBallState(enabled: Boolean) {
        if (enabled) {
            currentActivity?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    show(activity)
                }
            }
        } else {
            hide()
        }
    }

    private fun show(activity: Activity) {
        if (!AppConfig.debugLogFloatingBall) {
            return
        }
        if (isShowing || isAttaching) {
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        val rootView = activity.window.decorView as? ViewGroup ?: return

        currentActivity = activity
        isAttaching = true
        val currentToken = ++showToken

        try {
            val composeView = createComposeView(activity)
            floatingBallView = composeView
            rootView.post {
                if (!validateShowToken(currentToken, activity) || composeView.parent != null) {
                    isAttaching = false
                    floatingBallView = null
                    return@post
                }
                try {
                    rootView.addView(
                        composeView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        ).apply { gravity = Gravity.TOP or Gravity.START }
                    )
                    isShowing = true
                    isAttaching = false
                } catch (e: Exception) {
                    AppLog.put("调试悬浮球挂载失败：${e.message}", e)
                    isAttaching = false
                    floatingBallView = null
                }
            }
        } catch (e: Exception) {
            AppLog.put("调试悬浮球创建失败：${e.message}", e)
            isAttaching = false
            floatingBallView = null
        }
    }

    private fun hide() {
        if (!isShowing && !isAttaching) {
            return
        }
        showToken++
        isAttaching = false
        floatingBallView?.let { view ->
            view.postDelayed({
                try {
                    (view.parent as? ViewGroup)?.removeView(view)
                } catch (e: Exception) {
                    AppLog.put("调试悬浮球移除失败：${e.message}", e)
                }
            }, 50)
        }
        floatingBallView = null
        isShowing = false
    }

    private fun validateShowToken(token: Int, activity: Activity): Boolean {
        return token == showToken &&
            currentActivity === activity &&
            AppConfig.debugLogFloatingBall &&
            !activity.isFinishing &&
            !activity.isDestroyed
    }

    private fun createComposeView(context: Context): ComposeView {
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                LegadoComposeTheme {
                    DebugFloatingBallContent()
                }
            }
        }
    }

    @Composable
    private fun DebugFloatingBallContent() {
        Box(modifier = Modifier.fillMaxSize()) {
            DebugFloatingBall(
                onClick = {
                    currentActivity?.let { activity ->
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            hide()
                            activity.window.decorView.postDelayed({
                                try {
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        openPanel(activity)
                                    }
                                } catch (e: Exception) {
                                    AppLog.put("打开调试日志面板失败：${e.message}", e)
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        show(activity)
                                    }
                                }
                            }, 200)
                        }
                    }
                }
            )
        }
    }

    private fun openPanel(activity: Activity) {
        val appCompatActivity = activity as? AppCompatActivity ?: return
        val dialog = DebugLogDialog()
        // 面板关闭（Fragment 销毁）后恢复悬浮球
        dialog.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                activity.window.decorView.postDelayed({
                    if (AppConfig.debugLogFloatingBall &&
                        !activity.isFinishing &&
                        !activity.isDestroyed
                    ) {
                        show(activity)
                    }
                }, 200)
            }
        })
        appCompatActivity.showDialogFragment(dialog)
    }

}
