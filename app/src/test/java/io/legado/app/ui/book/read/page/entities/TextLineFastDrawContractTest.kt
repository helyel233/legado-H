package io.legado.app.ui.book.read.page.entities

import android.app.Application
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TextLineFastDrawContractTest {

    private var originalOptimizeRender: Boolean = false
    private var originalTextFont: String = ""
    private var originalTextWeight: Int = 400
    private var originalBgMeanColor: Int = 0

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        originalOptimizeRender = AppConfig.optimizeRender
        originalTextFont = ReadBookConfig.textFont
        originalTextWeight = ReadBookConfig.textWeight
        originalBgMeanColor = ReadBookConfig.bgMeanColor
        AppConfig.optimizeRender = true
        ReadBookConfig.textFont = ""
        ReadBookConfig.textWeight = 400
        ReadBookConfig.bgMeanColor = 0
        ChapterProvider.upStyle()
    }

    @After
    fun tearDown() {
        AppConfig.optimizeRender = originalOptimizeRender
        ReadBookConfig.textFont = originalTextFont
        ReadBookConfig.textWeight = originalTextWeight
        ReadBookConfig.bgMeanColor = originalBgMeanColor
        ChapterProvider.upStyle()
    }

    @Test
    fun `checkFastDraw returns false when hasCustomColumnLayout is true`() {
        val line = TextLine(
            text = "测试文字。",
            onlyTextColumn = true,
            exceed = false,
            hasCustomColumnLayout = true
        )
        assertFalse(line.checkFastDraw())
    }

    @Test
    fun `checkFastDraw returns true for standard text line when optimizeRender is enabled`() {
        val line = TextLine(
            text = "测试文字。",
            onlyTextColumn = true,
            exceed = false,
            hasCustomColumnLayout = false
        )
        assertTrue(line.checkFastDraw())
    }

    @Test
    fun `needsPerColumnThinStroke detects mixed Han and non-Han only under system font thin stroke`() {
        ReadBookConfig.textFont = ""
        ReadBookConfig.textWeight = 100
        ReadBookConfig.bgMeanColor = 0xFFFFFFFF.toInt()
        ChapterProvider.upStyle()

        // Mixed Han and Latin/digits -> requires per-column thin stroke
        assertTrue(ChapterProvider.needsPerColumnThinStroke(false, "测试ABC", 0, 5))
        assertTrue(ChapterProvider.needsPerColumnThinStroke(false, "中文123", 0, 5))
        // Pure Han with Chinese punctuation or whitespace -> safe for fastDraw
        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "测试。", 0, 3))
        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "  测试文字", 0, 6))
        // Pure non-Han -> drawThinStroke skips it safely in fastDraw
        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "Hello world!", 0, 12))
        // Range excludes non-Han prefix -> pure Han slice is safe
        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "AB测试", 2, 4))

        // Under system thin font (textWeight = 300), Chinese prose with punctuation keeps fastDraw
        // while mixed Han + Latin disables fastDraw.
        ReadBookConfig.textWeight = 300
        ReadBookConfig.textFont = ""
        ReadBookConfig.bgMeanColor = 0xFFFFFFFF.toInt()
        ChapterProvider.upStyle()

        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "中文测试，没有英文。", 0, 10))
        val chinesePunctLine = TextLine(
            text = "中文测试，没有英文。",
            onlyTextColumn = true,
            exceed = false,
            hasCustomColumnLayout = false
        )
        assertTrue(chinesePunctLine.checkFastDraw())

        assertTrue(ChapterProvider.needsPerColumnThinStroke(false, "中文 Android", 0, 10))
        val mixedAndroidLine = TextLine(
            text = "中文 Android",
            onlyTextColumn = true,
            exceed = false,
            hasCustomColumnLayout = false
        )
        assertFalse(mixedAndroidLine.checkFastDraw())

        // Mixed line disables checkFastDraw, pure Han keeps fastDraw enabled
        val mixedLine = TextLine(
            text = "　　测试ABC",
            indentSize = 2,
            onlyTextColumn = true,
            exceed = false,
            hasCustomColumnLayout = false
        )
        assertFalse(mixedLine.checkFastDraw())

        val pureHanLine = TextLine(
            text = "　　测试文字",
            indentSize = 2,
            onlyTextColumn = true,
            exceed = false,
            hasCustomColumnLayout = false
        )
        assertTrue(pureHanLine.checkFastDraw())

        // Out-of-bounds indentSize is clamped safely
        val clampedIndentLine = TextLine(
            text = "测试ABC",
            indentSize = 99,
            onlyTextColumn = true,
            exceed = false,
            hasCustomColumnLayout = false
        )
        assertTrue(clampedIndentLine.checkFastDraw())

        // Custom font or zero bgMeanColor or normal weight -> never needs per-column fallback
        ReadBookConfig.bgMeanColor = 0
        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "测试ABC", 0, 5))

        ReadBookConfig.bgMeanColor = 0xFFFFFFFF.toInt()
        ReadBookConfig.textFont = "custom.ttf"
        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "测试ABC", 0, 5))

        ReadBookConfig.textFont = ""
        ReadBookConfig.textWeight = 400
        ChapterProvider.upStyle()
        assertFalse(ChapterProvider.needsPerColumnThinStroke(false, "测试ABC", 0, 5))
    }
}
