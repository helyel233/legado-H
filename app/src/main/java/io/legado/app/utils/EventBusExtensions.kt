@file:Suppress("unused")

package io.legado.app.utils

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import com.jeremyliao.liveeventbus.LiveEventBus
import com.jeremyliao.liveeventbus.core.Observable

inline fun <reified EVENT> eventObservable(tag: String): Observable<EVENT> {
    return LiveEventBus.get(tag, EVENT::class.java)
}

inline fun <reified EVENT> postEvent(tag: String, event: EVENT) {
    LiveEventBus.get<EVENT>(tag).post(event)
}

inline fun <reified EVENT> postEventDelay(tag: String, event: EVENT, delay: Long) {
    LiveEventBus.get<EVENT>(tag).postDelay(event, delay)
}

inline fun <reified EVENT> postEventOrderly(tag: String, event: EVENT) {
    LiveEventBus.get<EVENT>(tag).postOrderly(event)
}

inline fun <reified EVENT> AppCompatActivity.observeEvent(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    val o = Observer<EVENT> {
        observer(it)
    }
    tags.forEach {
        eventObservable<EVENT>(it).observe(this, o)
    }
}

inline fun <reified EVENT> AppCompatActivity.observeEventSticky(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    val o = Observer<EVENT> {
        observer(it)
    }
    tags.forEach {
        eventObservable<EVENT>(it).observeSticky(this, o)
    }
}

inline fun <reified EVENT> Fragment.observeEvent(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    val o = Observer<EVENT> {
        observer(it)
    }
    tags.forEach {
        //绑定视图生命周期，避免视图销毁后回调仍触碰 binding
        eventObservable<EVENT>(it).observe(viewLifecycleOwner, o)
    }
}

inline fun <reified EVENT> Fragment.observeEventSticky(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    val o = Observer<EVENT> {
        observer(it)
    }
    tags.forEach {
        //绑定视图生命周期，避免视图销毁后回调仍触碰 binding
        eventObservable<EVENT>(it).observeSticky(viewLifecycleOwner, o)
    }
}

inline fun <reified EVENT> LifecycleService.observeEvent(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    val o = Observer<EVENT> {
        observer(it)
    }
    tags.forEach {
        eventObservable<EVENT>(it).observe(this, o)
    }
}

inline fun <reified EVENT> LifecycleService.observeEventSticky(
    vararg tags: String,
    noinline observer: (EVENT) -> Unit
) {
    val o = Observer<EVENT> {
        observer(it)
    }
    tags.forEach {
        eventObservable<EVENT>(it).observeSticky(this, o)
    }
}

/**
 * Compose 版事件订阅（2026-10-12，#27 会话接入）：把 LiveEventBus 事件转成 State。
 * 用法：`val msg by eventObservableAsState<String>(EventBus.CHECK_SOURCE, "")`
 * 生命周期跟随当前 CompositionOwner，销毁自动解绑。
 */
@androidx.compose.runtime.Composable
inline fun <reified EVENT> eventObservableAsState(
    tag: String,
    initial: EVENT,
): androidx.compose.runtime.State<EVENT> {
    val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(tag) {
        val observer = Observer<EVENT> { state.value = it }
        eventObservable<EVENT>(tag).observe(owner, observer)
        onDispose { }
    }
    return state
}