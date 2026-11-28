# 阅读页弹窗与内容域玻璃收口方案（定稿 · 修订版方案 B）

> 状态：方案定稿（未改业务代码）
> 范围：Phase 1 聚焦阅读页右上角设置项弹窗、阅读页确认类弹窗与内容域磨砂玻璃收口；Phase 2 覆盖 AI 面板、`EffectiveReplacesDialog` 及全仓 `selector()` / 其余非壁纸域页面按需治理。

---

## 1. 问题背景与根因基线

### 1.1 表面现象与底层根因

阅读页右上角点击后弹出的子设置项（翻页动画、图片样式、评论打开方式、快照保鲜期、离线缓存、模拟阅读、设置编码等）呈现明显割裂：部分是现代浮层，部分是老旧的原生 `AlertDialog` 列表或 XML 表单，且居中弹窗无法匹配底部「设置 / 界面」的正文磨砂玻璃质感。

根因并非某几个弹窗「用了手搓 UI」，而是两层历史债叠加：

1. **弹窗体系割裂**：同一阅读页混用 `ModernActionPopup`、`AppDialogFrame`（Compose）、旧 `selector()`（6 个重载均无锚点参数，定义在 `lib/dialogs/AndroidSelectors.kt`）和旧 XML `alert()`（`lib/dialogs/AndroidAlertBuilder.kt`）。
2. **内容域玻璃能力未抽成共享宿主协议**：阅读页关闭了壁纸透传（`showsWallpaperBehindContent = false`），无法复用壁纸域玻璃；当前 `MoreConfigDialog` 与 `ReaderBottomSheetFrame` 各自复制了一份调用 `ReaderSelectionShareCoordinator` 截屏并叠加磨砂的参数拼装逻辑，而 `AppDialogFrame` 没有拿到这套内容域背景，只能退回纯色卡片。

### 1.2 全仓代码实测基线

| 维度 | 实测基线 | 关键位置 |
|---|---|---|
| `selector(` 调用 | 68 处 / 30 文件 | 定义在 `lib/dialogs/AndroidSelectors.kt`（均无锚点） |
| `alert(` 调用 | 132 处 / 73 文件 | 定义在 `lib/dialogs/AndroidAlertBuilder.kt` |
| 非壁纸域页面（`showsWallpaperBehindContent = false`） | 4 个页面 | `ReadBookActivity.kt:314`、`BookInfoActivity.kt:332`、`AudioPlayActivity.kt:83`、`VideoPlayerActivity.kt:113`（消费在 `GlassSurface.kt:196`） |
| 截屏裁切硬编码 | 2 处贴底裁切 | `ReaderSelectionShareCoordinator.kt:82`、`ReaderSelectionShareCoordinator.kt:150` |
| 重复磨砂构造逻辑 | 2 处独立维护 | `MoreConfigDialog.kt:160-286`、`ReaderComposeComponents.kt:188-249` |

---

## 2. 方案选型与架构设计（修订版方案 B）

### 2.1 为什么选方案 B

- **相比纯局部修补（方案 A）**：若直接在 `AppDialogFrame` 里写私有分支去判断 `ReadBookActivity` 或调 `PixelCopy`，会让通用 Compose 弹窗组件反向依赖阅读页实现，且继续保留 `MoreConfigDialog` 与 `ReaderBottomSheetFrame` 两份重复磨砂代码。方案 B 通过极薄的宿主接口解耦，顺手把三处消费方收到同一套实现里。
- **相比全仓大改（方案 C）**：不在 Phase 1 一次性重写全仓 68 处 `selector()` 和 132 处 `alert()`，也不强行给所有非壁纸域页面硬上截屏玻璃（尤其是视频播放页有 `SurfaceView`/DRM 黑帧风险），把风险控制在阅读页闭环内。

### 2.2 核心架构改动

#### A. 新增共享协议 `ContentBackdropHost`（`ui/glass/`）

在 `app/src/main/java/io/legado/app/ui/glass/` 新增 2 方法接口，避免 `ui/widget/compose` 反向依赖 `ui/book/read`：

```kotlin
enum class BackdropCropGravity {
    BOTTOM,
    CENTER
}

interface ContentBackdropHost {
    fun captureContentBackdrop(
        gravity: BackdropCropGravity = BackdropCropGravity.BOTTOM,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Float
    ): Bitmap?

    fun contentGlassTintBaseColor(): Int
}
```

- `ReadBookActivity` 实现 `ContentBackdropHost`，委托给 `ReaderSelectionShareCoordinator`。
- `ReaderSelectionShareCoordinator.kt:64-156` 中的 `captureContentBackdrop`（`:82`）与 `captureWindowBackdrop`（`:150`）补充 `cropGravity` 参数：
  - `BOTTOM`（默认，保持现有底部弹窗行为不变）：`canvas.translate(0f, sampledHeight - source.height)`
  - `CENTER`（居中弹窗使用）：`canvas.translate(0f, (sampledHeight - source.height) / 2f)`

#### B. 合并重复的内容域磨砂构造器

抽取一处共享 helper（入参：`host`、`targetWidth`、`targetHeight`、`corners`、`gravity`、`glassStyle`），统一服务三个消费方：

1. `MoreConfigDialog`（`MoreConfigDialog.kt:160-286`）：`SurfaceCorners.ALL` + `BOTTOM`
2. `ReaderBottomSheetFrame`（`ReaderComposeComponents.kt:188-249`）：`SurfaceCorners.TOP` + `BOTTOM`
3. `AppDialogFrame`（`AppComposeDialogs.kt:203-336`）：`SurfaceCorners.ALL` + `CENTER`

**生命周期与降级链路（零新增失败模式）**：

- `AppDialogFrame` 通过 `DisposableEffect` 管理截屏 `Bitmap` 生命周期，弹窗销毁时主动 `recycle()`。
- 降级顺序与现有玻璃管线保持一致：
  - `CLEAR` 模式 → 走 `clearSurfaceColor` 半透明壳，不截屏。
  - `SOLID` 模式 / 墨水屏 / 低内存 / `blurRadius == 0f` / 宿主未实现 `ContentBackdropHost` / 截屏返回 `null`（含 `PixelCopy` 50ms 超时）→ 自动退回 `style.surface` 主题纯色底，不闪白、不阻塞 UI。

#### C. 简单单选项统一走 `ModernActionPopup`

- 复用右上角 `⋮` 或刷新按钮的锚点坐标（`ModernActionPopup.kt:432` 坐标重载）。
- 从溢出菜单二次弹出子浮层时，透传 `previousPopup`（`ModernActionPopup.kt:115`），衔接既有的 170ms 关闭动画，避免双浮层叠影。
- 选中态统一使用 `Action.checked`（由 `LegadoMiuixChoiceRow` 原生绘制），删掉 `"✓ $label"` 与 `"$label（当前）"` 字符串拼接。

---

## 3. Phase 1 详细施工清单（修订版）

### 3.1 内容域玻璃收口

| 文件 | 改动内容 |
|---|---|
| `ui/glass/ContentBackdropHost.kt`（新增） | 定义 `BackdropCropGravity`、`ContentBackdropHost` 与共享内容域磨砂 helper |
| `ui/book/read/ReaderSelectionShareCoordinator.kt` | `:82` 与 `:150` 支持 `BackdropCropGravity.BOTTOM` / `CENTER` |
| `ui/book/read/ReadBookActivity.kt` | 实现 `ContentBackdropHost` |
| `ui/book/read/config/MoreConfigDialog.kt` | 删除 `:160-286` 私有磨砂拼装，复用共享 helper |
| `ui/book/read/ReaderComposeComponents.kt` | 删除 `:188-249` 私有磨砂拼装，复用共享 helper |
| `ui/widget/compose/AppComposeDialogs.kt` | `AppDialogFrame`（`:203-336`）在内容域宿主存在时接入共享磨砂 + `DisposableEffect` 回收位图 |

### 3.2 阅读页右上角 7 处 `selector()` → `ModernActionPopup`

> 修订说明：原清点漏计了「图片样式」的经典菜单重复实现，实际为 **7 处**，本阶段将图片样式两处入口合并为同一方法。

| # | 功能 | 现位置 | 改造要点 |
|---|---|---|---|
| 1 | 翻页动画 | `BaseReadBookActivity.kt:561`（`showPageAnimConfig`） | 改为 `ModernActionPopup`，锚定右上角菜单位置，原生 `checked` |
| 2 & 3 | 图片样式（合并两处） | `ReadBookActivity.kt:1351`（`R.id.menu_image_style`）+ `ReadBookActivity.kt:5883`（`showImageStyle`） | **合并为一个共享方法**，统一走 `ModernActionPopup` + 原生 `checked` |
| 4 | 刷新长按选项 | `ReadBookActivity.kt:5792`（`showRefreshOptions`） | 改为 `ModernActionPopup`，锚定刷新按钮位置 |
| 5 | 评论打开方式 | `ReadBookActivity.kt:2395`（`showReviewOpenModeDialog`） | 改为 `ModernActionPopup`，移除 `"✓ $label"` 拼接，改用 `Action.checked` |
| 6 | 段评快照保鲜期 | `ReadBookActivity.kt:2416`（`showReviewSnapshotTtlDialog`） | 改为 `ModernActionPopup`，移除 `"$label（当前）"` 拼接，改用 `Action.checked` |
| 7 | EPUB 调度模式 | `ReadBookActivity.kt:3350`（`showEpubCoreScheduleModeDialog`） | 改为 `ModernActionPopup` + 原生 `checked` |

### 3.3 阅读页右上角 3 处 XML `alert()` 表单弹窗 → `AppDialogFrame`

| # | 功能 | 现位置 | 改造要点 |
|---|---|---|---|
| 1 | 离线缓存 | `BaseReadBookActivity.kt:439`（`showDownloadDialog`，当前用 `DialogDownloadChoiceBinding`） | 迁至轻量 `ComposeDialogFragment`（`AppDialogFrame` + 起止章数字输入），移除 `applyThemeEditBorder` |
| 2 | 模拟阅读 | `BaseReadBookActivity.kt:499`（`showSimulatedReading`，当前用 `DialogSimulatedReadingBinding`） | 迁至轻量 `ComposeDialogFragment`（`AppDialogFrame` + `AppDialogSwitchRow` + 文本输入）；**日期选择明确保留系统 `DatePickerDialog`**（仓内无 Compose 日期选择器，不自建） |
| 3 | 设置编码 | `BaseReadBookActivity.kt:528`（`showCharsetConfig`，当前用 `DialogEditTextBinding`） | 直接复用现有 `showComposeSuggestionTextInputDialog`（`ComposeDialogAdapters.kt:430`）传入 `charsets` 建议列表 |

### 3.4 阅读页确认类 `alert()` → `showComposeConfirmDialog`

> 修订说明：并非全部是 5 行无脑替换，以下第 1–3 项带有句柄复用或取消门控语义，必须逐条保真。

| # | 功能 | 现位置 | 关键语义约束 |
|---|---|---|---|
| 1 | 恢复阅读进度 | `ReadBookActivity.kt:4592`（`restoreLastBookProcess`） | 必须在 `onDismissAction` 中保留原 `onCancelled` 逻辑：`ReadBook.clearLastBookProgress()` 且 `confirmRestoreProcess = false`（依赖 `ComposeConfirmDialog` 的 `handledAction` 守卫，仅在取消/点外部关闭时触发） |
| 2 | 云端进度超过当前进度 | `ReadBookActivity.kt:5946`（`sureNewProgress`） | 原逻辑持有 `syncDialog: AlertDialog?` 并在重入前调 `syncDialog?.dismiss()`。替换为 `ComposeConfirmDialog` 后，必须保留可关闭句柄（持有 `ComposeConfirmDialog?` 引用或通过固定 Fragment tag `dismissAllowingStateLoss()`）防止重复叠加 |
| 3 | 退出提示加入书架 | `ReadBookActivity.kt:5967`（`finish()`） | 必须在 `onDismissAction` 中保留原 `setOnCancelListener` 的 `shelfExitRequestGate.cancel()`，否则点返回键/空白取消后会卡住退出门控 |
| 4 | 删除 AI 配图确认 | `ReadBookActivity.kt:5205`（`confirmDeleteAiInsertedImage`） | 直接迁至 `showComposeConfirmDialog(dangerPositive = true)` |
| 5 | 同步进度确认 | `ReadBookActivity.kt:5345`（`sureSyncProgress`） | 直接迁至 `showComposeConfirmDialog` |
| 6 | 章节付费确认 | `ReadBookActivity.kt:4631`（`payAction`） | 直接迁至 `showComposeConfirmDialog` |

### 3.5 Phase 1 明确不纳入项

- **`ContentEditDialog`**：全屏编辑器（`setLayout(1f, MATCH_PARENT)` + 工具栏菜单），不属于设置弹窗，**永久排除**。
- **`EffectiveReplacesDialog`**：含 `RecyclerView` + `registerForActivityResult` + 嵌套 `alert` + `onDismiss` 写库，复杂度独立，**延后至 Phase 2**。
- **自定义 Compose 日期选择器**：不造轮子，模拟阅读继续调用系统 `DatePickerDialog`。

---

## 4. Phase 2 远期收口清单

Phase 1 稳定后按模块分批推进，不阻塞当前主线：

1. **阅读页 AI 面板 4 处 `selector()`**：
   - `ReadAiFloatingPanel.kt:852`
   - `ReadAiFloatingPanel.kt:870`
   - `ReadAiFloatingPanel.kt:914`
   - `ReadAiSummaryPanel.kt:318`
2. **`EffectiveReplacesDialog` 迁移**：在保留 `ActivityResultLauncher` 与 `onDismiss` 写库回调的前提下迁至 `AppDialogFrame`。
3. **全仓剩余 `selector()` 分流收口**（按模块：`ui/config/`、`ui/book/cache/`、`ui/book/toc/`、`ui/widget/dialog/` 等）：
   - 有明确点击锚点的菜单/设置项 → `ModernActionPopup`
   - 无锚点/全局触发的列表选择 → `showComposeChoiceListDialog`
   - 收口完毕后给 `lib/dialogs/AndroidSelectors.kt` 标 `@Deprecated`，阻止新代码回流。
4. **其余非壁纸域页面按需接入 `ContentBackdropHost`**：
   - `BookInfoActivity`、`AudioPlayActivity`：视需求按需实现 `ContentBackdropHost`，自动获得 `AppDialogFrame` 内容域磨砂。
   - `VideoPlayerActivity`：**默认不接入**（`SurfaceView`/DRM 画面截屏易出黑帧，继续走纯色降级更稳妥）。

---

## 5. 二阶、三阶影响与风险对策

| 维度 | 潜在影响 | 对策 |
|---|---|---|
| **性能与内存** | 每次打开居中 `AppDialogFrame` 触发一次截屏与模糊；频繁开关可能造成位图堆积或主线程抖动 | 复用已有降采样与 50ms `PixelCopy` 超时保护；`DisposableEffect` 退出即 `recycle()`；低内存/墨水屏/SOLID 模式直接跳过截屏 |
| **交互与门控回归** | `sureNewProgress` 重复弹出、`finish()` 取消后退出门控卡死、`restoreLastBookProcess` 取消未清状态 | 严格按 §3.4 保留 `syncDialog` 句柄与 `onDismissAction` 门控逻辑，并补充单测/真机回归项 |
| **浮层叠影** | 从右上角溢出菜单切到子浮层时，旧浮层 170ms 关闭动画与新浮层重叠 | 所有二次弹出统一透传 `previousPopup`，复用 `ModernActionPopup` 既有交接时序 |
| **长期维护成本** | 若继续放任 `selector()` / `alert()` 新增，玻璃与交互会再次分叉 | Phase 1 先消除阅读页内重复实现（合并两处图片样式、合并两处磨砂构造）；Phase 2 标 `@Deprecated` 堵住入口 |

---

## 6. 验收与测试清单

### 6.1 自动化检查

- 编译通过：`./gradlew :app:compileAppDebugKotlin`
- 既有单测无回归，补充 `BackdropCropGravity` 裁切偏移计算（`BOTTOM` vs `CENTER`）的纯 JVM 单测。
- 静态核查：阅读页（`ReadBookActivity.kt`、`BaseReadBookActivity.kt`）不再存在 Phase 1 清单内的 `selector(` 与目标 `alert(` 调用，且无 `"✓ "` / `"（当前）"` 选中态拼接残留。

### 6.2 真机手工验证

1. **玻璃模式全覆盖**：分别在玻璃（磨砂）、清晰（CLEAR）、纯色（SOLID）、墨水屏模式下打开离线缓存、模拟阅读、设置编码、确认类弹窗，验证居中正文磨砂质感与底部「设置 / 界面」一致，降级无闪白。
2. **右上角 7 处单选浮层**：逐一触发（含经典菜单与 Compose 顶栏两处图片样式入口），验证锚点贴合、无双浮层叠影、原生勾选态准确、切换后立即生效。
3. **关键门控与句柄回归**：
   - `sureNewProgress` 连续触发两次，确认只存在一个弹窗且旧实例被关闭。
   - `finish()` 提示加入书架时按系统返回键/点弹窗外取消，再次按返回键可正常弹出且不卡死。
   - `restoreLastBookProcess` 取消后，不再重复弹窗且进度状态清理正常。
   - 模拟阅读点击日期字段，系统 `DatePickerDialog` 正常弹出并回填日期。
