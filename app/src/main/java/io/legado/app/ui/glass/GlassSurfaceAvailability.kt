package io.legado.app.ui.glass

import io.legado.app.ui.main.GlassMaterialPreset

/**
 * Compose 桥在**组合期**就要决定「这一格画玻璃层，还是回退原有背景」。
 * 判定必须与 `ExploreGlassBackdrop.glassDrawable()` 的早退分支逐条同源：
 *
 * 1. 采样域不是壁纸域 → 入口返回 null（面板域 / 内容域不可用壁纸采样）；
 * 2. 无玻璃档 → 返回不透明实底壳，任何时候都可用；
 * 3. 墨水屏 / 低内存设备 → 壁纸采样降级为 null。
 *
 * 顺序有意义：无玻璃档在设备降级之前判定，所以「墨水屏 + 无玻璃档」仍然可用（实底），
 * 与 `glassDrawable()` 的实际产物一致。任何一层新增早退分支都必须同步这里，否则
 * Compose 会先按玻璃布局、再拿到 null 背景，表现为卡片透明。
 */
fun glassSurfaceAvailable(
    domain: GlassSamplingDomain,
    mode: String,
    isEInk: Boolean,
    isLowRam: Boolean
): Boolean = glassSurfaceDrawableAllowed(domain) &&
    (mode == GlassMaterialPreset.SOLID || !(isEInk || isLowRam))
