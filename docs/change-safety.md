# legado-H 改动安全网规范（change-safety）

> 本文件是从 2026-10-17~11-17 一个月复盘（`output/bug-review-2026-11-17.md`）沉淀出的**可执行约定**。
> 每一条都对应一个真实返工案例，违反任意一条都等于重新支付一遍已经付过的成本。
> 与 `docs/release-process.md`（发版规范）互补：本文件管「改代码」，那份管「发版本」。

---

## R1. 交付闭环：代码改完 ≠ 用户看到

**背景案例**：玻璃三页 5 轮「修了没变化」，根因是修复代码从未编译进用户设备上的 APK，前五轮只验证源码和单测。

**约定**：任何面向用户的修复，收尾必须走完这条闭环，缺一步不得声称「已修复」：

1. `compileAppDebugKotlin` 通过（编译正确性）；
2. 相关定向单测通过（纯逻辑正确性）；
3. **`assembleAppDebug` 出包**；
4. **装机并核对包身份**——`adb install -r` 后用 `md5sum`（本地 base.apk ↔ 设备 `/data/app/.../base.apk`）或 `dumpsys package versionName` 确认装的是**这次构建的包**，不是旧包；
5. **真机截图取证**（渲染类改动必须，像素级确认，不靠肉眼口头确认）。

**铁律**：
- 没有 adb（设备离线/断连）就**不要声称**「实机已验证」。改口说「已构建、待装机」。
- adb 用全路径 `/Users/zego/Library/Android/sdk/platform-tools/adb`（`which adb` 是 not found，别被误导）。
- 截图坐标换算：真机 1260×2800，展示图常被缩放（约 2.5×），点击物理坐标 = 展示坐标 × 缩放倍数，每次先量再点。

---

## R2. 唯一实现：同一能力只许一套管线

**背景案例**：App 里并存两套玻璃系统——A 套 AGSL（`RowGlassLiquidRenderer`，用户认可）与 B 套手写 Canvas（`GlassFrostDrawable`，假玻璃）。三页落在 B 套，反复调 B 套参数永远不像 A 套，被连续驳回。

**约定**：
1. 加新能力前，先 `grep` 是否已有同类实现；**能复用 A 套就不要新写 B 套**。
2. 玻璃渲染**唯一入口**是 `ui/glass/` 下经 `GlassSurface`/`GlassPanel`/`ExploreGlassBackdrop` 分派的管线，底层唯一 shader 是 `RowGlassLiquidRenderer`。新增页面只许接线，**禁止再手写 Canvas 假玻璃**（`BlurMaskFilter` / `stackBlurSoftware` / RenderScript 兜底只作降级基线，不作主实现）。
3. 参数表唯一来源：`GlassMaterialPreset`。不允许任何页面另起一份「同义但不同源」的参数表（历史上「单一来源收拢」已反复 9 次）。
4. 写死开关是红线：`useComposeBookInfo = false` 这类把一条渲染路径硬编码关闭的死开关，会让「改了但没生效」。要么删掉死路径，要么把开关接成真实可切换的状态并登记在双轨清单里。

**双轨渲染清单（XML/Classic vs Compose 并存时）**：改任何「同一功能多渲染路径」的页面，**必须逐条查分流函数**（如 `BookInfoNavigator.targetClass()`、`BookInfoPageStyle.fromKey`），把所有存活路径都改到位，再交付。只改一条 = 没改。

---

## R3. 先验根因，再调参数

**背景案例**：玻璃「半透明但模糊看不清」，前两轮从「系统窗口模糊不可验证」「截图取不到像素」找原因，方向全错；真正根因是一行位运算 `0xff000000.toInt() shl 24` 让整张模糊位图 alpha=0。

**约定**：视觉/渲染类缺陷，按此顺序排查，不许跳过第一步直接调参：

1. **先验「产物是否存在、是否可见」**——模糊类问题先验 alpha 是否非零、drawable 是否真的画了（不是先验模糊强度）；
2. 再验「管线走对没有」——采样源是否合法、有没有命中降级回退、AGSL 是否生效（看 logcat 有没有降级日志）；
3. 最后才谈「强度/观感」调参。

**位运算反模式（已固化为 CI 门禁，见 `tools/check_glass_governance.py`）**：
```kotlin
// ❌ 错：0xff000000.toInt() 已是 -16777216，再 shl 24 结果恒 0
pix[yi] = (0xff000000.toInt() shl 24) or (r shl 16) or (g shl 8) or b
// ✅ 对：直接写 alpha
pix[yi] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
// ✅ 对：alpha 变量再移位（alpha 本身不含高位）
pix[yi] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
```
`alpha shl 24` 是对的；`<带 alpha 的常量> shl 24` 是错的。门禁脚本只拦后者。

---

## R4. 状态单一真相源：独立状态键，不用数值反推

**背景案例**：用「当前强度 == 预设值 70」反推是否预设，但主题包启动时用 60 覆盖强度 → 重启后误判「自定义」。

**约定**：
1. **禁止用「可被其他入口覆盖的数值」反推 UI 状态**。凡是状态，用独立状态键（布尔/枚举），选态时写 true、手动改时写 false（参考 `glassPresetMatched`）。
2. 引入任何会被多个入口共享写入的偏好键前，先想清楚「谁会在启动/切主题/迁移时覆盖它」，把这些写入点列进 KDoc。
3. 窗口/系统栏这类**粘性 flag**（`setDecorFitsSystemWindows`、`LAYOUT_FULLSCREEN`）必须**同向设置**，不得一处 `true` 一处 `LAYOUT_FULLSCREEN`（历史 `fullScreen()` 就是两者矛盾导致「内容铺进状态栏」）。

---

## R5. 原子提交：一个 commit 一个可回滚单元

**背景案例**：`345dbd0c6` 与 `7342e7f78` 同一改动提交两次（一次无日志一次补日志）；`b4d65e07e` 一个 commit 塞 81 文件；回滚因果散落在 commit message 里。

**约定**：
1. **功能改动与「定稿日志」分开提交**：先提交代码改动（带清晰的改动说明），再单独提交 `定稿 X.Y.Z 日志`（只碰 CHANGELOG.md / updateLog.md）。禁止把代码 + 日志 + 版本号塞进同一 commit。
2. 一个 commit 尽量对应一个可独立回滚的单元；大改动（如全 App 玻璃接入）应拆分「纯函数抽取 → 接线 → 收口」多步提交，而不是一坨。
3. **回滚必须留痕**：任何 `reset`/`revert`/「回退管线」，commit message 里必须写明「回退了什么、为什么、留下了哪些仍被引用的守卫/产物」，让后来者能判断死代码边界（参考 `0e44ffbf1` 的写法）。

---

## 落地方式

- **人工**：改动前过一遍本文件 R1~R5；交付前再对一遍。
- **自动化**：`tools/check_glass_governance.py`（alpha 位运算反模式，已进 `verify.yml` CI 门禁）。
- **Skill**：`.workbuddy/skills/legado-change-safety/` 把本文件的检查清单做成可触发执行步骤，改动前/交付前各跑一遍。
