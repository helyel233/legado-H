package io.legado.app.reader.config

import io.legado.app.constant.EventBus
import io.legado.app.utils.postEvent

/**
 * P3-a: typed replacement for the legacy UP_CONFIG int-code channel.
 *
 * P3-c: this is now the only channel (legacy UP_CONFIG int channel removed);
 * legacyCodes is retained as documentation of the historical mapping.
 *
 * Legacy code table (from ReadBookActivity.handleReadConfigUpdate):
 *   0 system bars, 1 background, 2 header/footer/tip content, 3 bg alpha,
 *   4 page touch slop, 5 relayout, 6 tip style, 8 typography(+relayout),
 *   9 invalidate text page, 10 typography (≈8), 11 submit render,
 *   12 page click, 13 EPUB engine changed (must be posted alone)
 */
sealed interface ReadConfigEvent {
    val legacyCodes: List<Int>

    /** 状态栏显隐 */
    data object SystemBars : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(0)
    }

    /** 背景（颜色/图片） */
    data object Background : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(1)
    }

    /** 页眉/页脚/tip 内容 */
    data object HeaderFooterTips : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(2)
    }

    /** 背景透明度 */
    data object BackgroundAlpha : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(3)
    }

    /** 翻页触摸区域 */
    data object PageTouchSlop : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(4)
    }

    /** 重排版 */
    data object Relayout : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(5)
    }

    /** 页眉页脚/提示样式 */
    data object TipStyle : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(6)
    }

    /** 排版样式（字体/字重/字距/行距/下划线等，含样式刷新与重排；原 8/10） */
    data object Typography : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(8)
    }

    /** 文字页失效重绘（原 9） */
    data object InvalidateTextPage : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(9)
    }

    /** 提交渲染任务（原 11） */
    data object SubmitRender : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(11)
    }

    /** 点击区域行为（原 12） */
    data object PageClick : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(12)
    }

    /** EPUB 排版引擎切换（原 13；消费端最高优先级，必须单独发送） */
    data object EpubEngineChanged : ReadConfigEvent {
        override val legacyCodes: List<Int> get() = listOf(13)
    }

    companion object {

        /** 语义化发送（P3-c 起为唯一通道，旧 UP_CONFIG 数字通道已移除）。 */
        fun post(vararg events: ReadConfigEvent) {
            postEvent(EventBus.READ_CONFIG_V2, ArrayList(events.toList()))
        }
    }
}
