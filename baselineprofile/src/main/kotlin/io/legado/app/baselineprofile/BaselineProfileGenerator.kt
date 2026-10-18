package io.legado.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * 基线性能数据采集
 *
 * 冷启动与首屏滚动的关键路径被 JIT 逐行编译，前几秒必然卡顿；
 * 把这份基线文件打进 APK，装到支持 profileinstaller 的设备上，
 * ART 就会按记录的轨迹做 AOT 预编译，把"首次用才编译"提前到"安装时编译"。
 *
 * app 侧早已依赖 `androidx.profileinstaller`（消费侧就绪），
 * 所以这里补上生成侧，产出 `baseline-prof.txt` 并由插件合并进 APK。
 *
 * ## 怎么跑
 *
 * 需要一台 **API 28+ 且已 root** 的设备（物理机或模拟器都可以，
 * 物理机要 root，因为要写系统 ART profile；模拟器用 API 33 镜像即可）。
 *
 * ```
 * adb devices
 *
 * # 生成基线文件（默认走真机，产物在 baselineprofile/build/outputs/ 与
 * # baselineprofile/src/main/generated/baselineProfiles/）
 * ./gradlew :baselineprofile:generateBaselineProfile
 *
 * # 生成后连跑两轮对比冷启动耗时
 * ./gradlew :baselineprofile:pixel6api33BaselineProfile -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=MacrobenchmarkRule
 * ```
 *
 * ## 正式包（appDebug）怎么用上基线
 *
 * 插件默认只给 **release** 变体生成基线，但本项目正式分发的是 appDebug
 * （applicationIdSuffix = `.debug`）。所以生成之后还要把文件复制过去：
 *
 * ```
 * # AGP 会把 app/src/main/baseline-prof.txt 合并进所有变体（含 debug），
 * # profileinstaller 已就位，装机后即生效。
 * cp baselineprofile/src/main/generated/baselineProfiles/baseline-prof.txt \
 *    app/src/main/baseline-prof.txt
 * ```
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /**
     * 冷启动轨迹：从桌面图标点击到书架首屏可交互。
     *
     * Launcher 是 `WelcomeActivity`，但它是纯中转页（立刻 finish 并跳 MainActivity），
     * 所以这条轨迹覆盖的是真实的"点击图标进书架"路径。
     */
    @Test
    fun startup() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            waitForShelf()
        }
    }

    /**
     * 书架滚动轨迹：封面图、列表测量、图片解码这些只在滚动时才会走到的方法，
     * 不进启动 profile（否则启动 AOT 编译会变慢），单独采集更划算。
     *
     * 项目里几乎没有 testTag / viewId，所以这里不按控件 ID 定位，
     * 直接对当前窗口做通用滑动手势；书架为空（空态页）时滑动手势无对象可滚，
     * 但不影响 profile 收集。
     */
    @Test
    fun shelfScroll() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false
        ) {
            pressHome()
            startActivityAndWait()
            waitForShelf()

            val width = device.displayWidth
            val height = device.displayHeight
            val fromX = width / 2
            val startY = (height * 0.75f).toInt()
            val endY = (height * 0.25f).toInt()

            // 连滑几屏，让封面加载、列表测量、分组切换这些方法都被覆盖到
            repeat(SCROLL_PAGES) {
                device.swipe(fromX, startY, fromX, endY, SWIPE_STEPS)
                device.waitForIdle()
            }

            // 回到顶部，覆盖反向滚动的轨迹
            device.swipe(fromX, endY, fromX, startY, SWIPE_STEPS)
            device.waitForIdle()
        }
    }

    /**
     * 等书架首屏稳定。
     *
     * 优先等到出现可滚动列表；书架为空时列表页可能整页都是空态，
     * 超时也不抛异常（只是少采集一段轨迹），避免空书架直接让基准失败。
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.waitForShelf() {
        device.wait(Until.hasObject(By.scrollable(true)), SETTLE_TIMEOUT_MS)
        device.waitForIdle()
    }

    private companion object {
        /**
         * 正式分发的包打的是 appDebug 变体（applicationIdSuffix = `.debug`），
         * 所以基线要对着这个包名采集，profileinstaller 才会真正用上。
         * release 变体是 `io.legado.app.LegadoH`，换变体采集时改这里。
         */
        const val TARGET_PACKAGE = "io.legado.app.debug"

        private const val SETTLE_TIMEOUT_MS = 10_000L
        private const val SCROLL_PAGES = 4
        private const val SWIPE_STEPS = 60
    }
}
