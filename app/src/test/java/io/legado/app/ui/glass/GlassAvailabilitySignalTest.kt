package io.legado.app.ui.glass

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ThemeConfig
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.putPrefString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import splitties.init.injectAsAppCtx

/**
 * `rememberGlassAvailability` 的**信号通路**验证（本轮 Compose 重判收口的最后一块地基）。
 *
 * 该机制的成立依赖三个外部事实，任何一个不成立，监听器就白挂：
 *
 * 1. 背景图开关落在 `PreferenceManager.getDefaultSharedPreferences`（
 *    `io.legado.app.utils.defaultSharedPreferences`）这一个文件里——若它被搬去别的文件，
 *    挂在默认文件上的监听器永远收不到；
 * 2. `PreferKey.bgImage` 的写入会真的回调（并且回调里能读到新值）；
 * 3. `ThemeConfig.hasUsableBgImage`（`pageGlassAllowed` 的壁纸那一半）会随该写入翻转。
 *
 * 这里用真实偏好 + 真实文件走一遍，断言上面三条。**不 mock SharedPreferences**：mock 掉就
 * 等于验证假的通路。
 *
 * 说明：Robolectric 默认没有可用背景图，所以本类先断言初始为 false，再用一张真实可读的
 * 文件把状态翻到 true——这也是本仓库单测里唯一能把 `hasUsableBgImage` 拨到 true 的方式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class GlassAvailabilitySignalTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().also { it.injectAsAppCtx() }
        // 每个用例从「没有背景图」开始：默认偏好是进程级共享的，不清会互相污染
        context.putPrefString(PreferKey.bgImage, "")
        context.putPrefString(PreferKey.bgImageN, "")
    }

    @Test
    fun `default prefs carry the background image switch`() {
        // 事实 1：`defaultSharedPreferences` 就是监听器要挂的那个文件，且背景图是其中的键
        val prefs = context.defaultSharedPreferences
        assertFalse(
            "前置：未配置背景图时不应可用",
            ThemeConfig.hasUsableBgImage(context)
        )

        prefs.registerOnSharedPreferenceChangeListener(NOOP)
        val file = writeReadableImage()
        context.putPrefString(PreferKey.bgImage, file.absolutePath)
        prefs.unregisterOnSharedPreferenceChangeListener(NOOP)

        assertTrue(
            "写入 PreferKey.bgImage 后 hasUsableBgImage 必须翻转，否则 GlassPanel 的监听器"
                + "即使收到回调也判不出变化",
            ThemeConfig.hasUsableBgImage(context)
        )
    }

    @Test
    fun `writing the background image switch notifies default prefs listeners`() {
        // 事实 2：这是 rememberGlassAvailability 唯一的触发源
        val prefs = context.defaultSharedPreferences
        val seenKeys = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            seenKeys += key
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            val file = writeReadableImage()
            context.putPrefString(PreferKey.bgImage, file.absolutePath)
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }

        assertTrue(
            "关闭/开启背景图必须回调到默认偏好监听器，实际收到：$seenKeys",
            seenKeys.contains(PreferKey.bgImage)
        )
    }

    @Test
    fun `clearing the background image flips the switch back within the callback`() {
        // 事实 3：回调里读到的必须是**新值**（监听器内调 compute() 才拿得到翻转后的结论）
        val prefs = context.defaultSharedPreferences
        val file = writeReadableImage()
        context.putPrefString(PreferKey.bgImage, file.absolutePath)
        assertTrue(ThemeConfig.hasUsableBgImage(context))

        var observedInCallback: Boolean? = null
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PreferKey.bgImage) {
                observedInCallback = ThemeConfig.hasUsableBgImage(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            context.putPrefString(PreferKey.bgImage, "")
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }

        assertEquals(
            "回调内必须已能读到「不可用」——rememberGlassAvailability 就是在回调里 compute()",
            false,
            observedInCallback
        )
        assertFalse(ThemeConfig.hasUsableBgImage(context))
    }

    @Test
    fun `unregistering stops the callbacks`() {
        // 监听器必须能解绑：LazyColumn 行滚出时 onDispose 走的就是这条
        val prefs = context.defaultSharedPreferences
        var count = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> count++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        context.putPrefString(PreferKey.prevKeys, "a")
        val afterRegister = count
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        context.putPrefString(PreferKey.prevKeys, "b")

        assertTrue("注册期间必须收到回调，实际 $afterRegister", afterRegister > 0)
        assertEquals("解绑后不得再收到回调", afterRegister, count)
    }

    private fun writeReadableImage(): File {
        val dir = File(context.filesDir, "bg-image-test").apply { mkdirs() }
        val file = File(dir, "wallpaper.png")
        // 内容只需可读：isReadableThemeFile 只判可打开，不解码
        file.writeBytes(ByteArray(16))
        return file
    }

    private companion object {
        /**
         * `registerOnSharedPreferenceChangeListener` 的实参不能是内联 lambda 字面量以外的
         * 临时对象——但也不需要持引用，这里的用途只是占位一次注册/解绑配对。
         */
        val NOOP = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> }
    }
}
