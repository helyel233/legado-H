# Legado-H 第四轮 UI 底层架构与包体系演进方案（V4 · 事实核验定稿版）

> **版本裁决与演进声明（Supersedes R3 Runtime Override Write-Back）**
> 1. 本方案正式确立 **「三层正交解耦 + 双物理包/槽位挂载 + 级联 Token 引擎 + 硬件快照门控过渡」** 架构。
> 2. 正式 **废除（Supersede）** `ui-architecture-v3-plan.md` 中的「拖动外观滑块反向改写磁盘 `theme.json`」语义（`ThemePackageManager.updateActiveRuntimeOverride` 不再污染包文件）。
> 3. 本轮改造严格按 **P0 → P4** 切片推进，确保每阶段可独立度量、可证伪、可回滚。

---

## 一、 代码事实基线（Fact Baseline）

| 架构领域 | 代码库真实事实（Fact Baseline） | 对架构设计的直接约束 |
| :--- | :--- | :--- |
| **1. 包管理器与同步 UI 的真实分布** | 5 个管理/配置类共 **5,815 行**（`AppearanceKitManager.kt` 1366 行、`ThemePackageManager.kt` 1651 行、`TopBarConfig.kt` 643 行、`NavigationBarIconConfig.kt` 1466 行、`CoverCollectionManager.kt` 689 行）。<br>• **Manager 层重复的是 ZIP 解压/打包与本地文件 IO**；<br>• **WebDAV 同步任务与导入导出交互实际散落在多个 Manage Activity**（Theme / TopBar / NavigationBar / Appearance / Bubble 等）。 | 既要收敛底层 IO 内核（`PackageIoKernel`），也要同步收敛上层 **Manage Activity 的导入/导出/云同步 UI 管线**（`PackageManageUiDelegate`）；`TopBarConfig` 已被全局覆盖架空，并入主美术包壁纸槽，清理独立顶栏包管理入口。 |
| **2. `updateActiveRuntimeOverride` 的真实性质** | `ThemePackageManager.updateActiveRuntimeOverride` 已在 `withContext(IO)` + `importMutex` 内执行。它的真正问题是**语义污染**（拖动圆角/透明度滑块会修改磁盘上当前主题包的 `theme.json`），**而非主线程卡顿源**。 | 必须切断“调运行时参数反写美术包文件”的链路，严禁将其混入性能账单。 |
| **3. XML 页面无法实现“零 `recreate()` 真热更”** | 全仓 **128 个 XML layout 直接硬编码 `@color/`**，而 `colors.xml` 是纯静态 hex（**0 个 `?attr`**），`BaseActivity` 仅在 inflate 前 `setTheme(Light/Dark)`。XML 页面的配色在 inflate 瞬间即被冻结。 | • **Compose 主导页 + 主界面壳层**：可实现零 `recreate()` 真热更；<br>• **存量 XML 页面**：换色**仍必须走 `recreate()`**。因此**离屏硬件快照过渡是掩盖 XML 重建闪屏的承重墙**，绝非锦上添花的动效，且 P4 阶段绝不能粗暴删除 `EventBus.RECREATE`。 |
| **4. 现有 `recreate()` 路径本身的脆弱性** | `MainActivity.kt` 注释已证明：`recreate()` 若与系统配置分发同帧会被 `ActivityThread` 合并丢弃，导致经典 View 页停留在旧主题，才被迫加了 1500ms 安全网。 | 必须先用**“硬件快照捕获 + 新页面首帧绘制完成门控 + 重建调度”**把不可靠的 `recreate()` 变可靠，再逐步缩小其触发范围。 |
| **5. 空间 Token 现状** | `AppUiTokens.kt` 中已存在静态 `AppLayoutTokens`，但尚未接入动态 `densityScale`，且存在 3 处边距契约冲突（详情页 18/16dp、卡外标题 4/0dp、容器与卡片双重边距风险）。 | 动工前定死这 3 个空间基准数，再将 `AppLayoutTokens` 从静态 `object` 升级为由 `@layout` 编译产出的动态 Token。 |
| **6. 性能缺陷与包体系的解耦** | 主线程同步解码壁纸+`stackBlur`（`BaseActivity.upBackgroundImage` → `ThemeConfig.getBgImage`）、全树递归刷字体（`BaseActivity.applyUiBodyTypefaceDeep`）、4 连发玻璃预热定时器（`MainActivity` `48/180/420/900ms`）**与包体系完全无关**。 | 剥离为 **P0 独立性能修复 + 基线度量**，先行落地验证，不与大重构混杂归因。 |

---

## 二、 四个已拍板的架构裁决（Confirmed Architecture Decisions）

### 裁决 ①：状态真相源（Single Source of Truth）
* **已确认方案**：**运行时 `PreferKey`（经由 `PrefBatchTransaction` 封装）为唯一运行时状态真相源；`ParamPackage` 为不可变预设快照（Immutable Preset Snapshot）**。
* **运作机制**：
  1. **只读预设包，绝不偷偷反写磁盘包**：磁盘上的 `ArtPackage` 和 `ParamPackage` 一律视为只读档案。用户在「外观中心」拖滑块或在「界面布局」切开关，**只写运行时 `PreferKey`**。
  2. **偏离检测（Dirty State）**：运行时 `PreferKey` 与当前激活的 `ParamPackage` 基准快照不一致时，标记为“已修改”，支持用户主动「覆盖当前参数包」或「另存为新参数包」。
  3. **单向投影与捕获**：应用参数包时按用户勾选的分区（`@surface` / `@layout`）批量写入 `PreferKey`；导出/保存时从 `PreferKey` 单向捕获快照。

### 裁决 ②：XML 侧重建承重墙 —— 硬件离屏快照控制器
* **已确认方案**：实现 `WindowSnapshotTransitionController`（硬件捕获 + 输入拦截 + 首帧绘制完成门控 + 墨水屏 0ms 直切）。
  1. **捕获**：API 26+ 使用 `PixelCopy.request(window, ...)` 捕获包含 RenderEffect / AGSL 液态玻璃的真实硬件合成结果（超时/失败回退实色遮罩）；跨 `recreate()` 时暂存于进程级 `SnapshotHolder`，新 Activity 首帧挂载前铺至 `decorView` 顶层。
  2. **输入拦截**：遮罩层在过渡结束前拦截全部触摸事件。
  3. **首帧门控**：严禁固定延时启动动画，必须在底层新树 `doOnPreDraw` + `registerFrameCommitCallback`（或双帧 `Choreographer`）确认真正完成首帧绘制后才启动揭露，并配 600ms 硬超时兜底。
  4. **揭露与降级**：常规屏走 `CircularReveal`（API 30+）或硬件 `Alpha` 淡出；**墨水屏强制 0ms 直切**。

### 裁决 ③：4 个 Scope × 2 条渲染路径 = 8 套显式接线
* **首批核心区域 Scope**：`TOP_BAR`（顶栏）、`BOTTOM_BAR`（底栏/侧栏）、`BOOKSHELF_CARD`（书架卡片）、`DIALOG`（弹窗浮层）。
* **反隐式继承规则**：Compose 的 `CompositionLocal` 穿不过独立 `Dialog` Window 与 XML `RecyclerView`。必须通过纯函数 `ResolvedUiTokens.resolveScope(scope)` 计算，并在 8 条渲染路径上显式注入：

| Scope 作用域 | Compose 渲染路径落点 | XML / View 渲染路径落点 |
| :--- | :--- | :--- |
| **`TOP_BAR`** | `AppComposeTopBar` 直接读取 `tokens.topBar` | `TitleBar.kt` / `MainTopBarView.kt` 通过 `ViewTokenBinder` 读取 `tokens.topBar` |
| **`BOTTOM_BAR`** | Compose 底栏/动作栏读取 `tokens.bottomBar` | `MainActivity` 底栏/侧边栏读取 `tokens.bottomBar`（清理 `MainActivity.kt` 3 处 `by lazy { getDimension }` 硬编码） |
| **`BOOKSHELF_CARD`** | Compose 列表/网格卡片读取 `tokens.bookshelfCard` | `BookshelfFragment1/2` 的 `RecyclerView.Adapter` 由 Fragment 注入 `tokens.bookshelfCard` 并局部 payload 刷新 |
| **`DIALOG`** | `AppDialogFrame` 在 Dialog Window 根部**直接读取 `tokens.dialog` 并重新注入 `CompositionLocalProvider`** | `BaseDialogFragment` 与 `AndroidAlertBuilder` 通过 `SurfaceStyles.dialog` 读取 `tokens.dialog`（修通 `SurfaceStyle.themePanelImagePath()` 旁路） |

### 裁决 ④：空间基准三项定数（锁定 `AppLayoutTokens`）
1. **`DetailHorizontal`**：统一由 `18.dp` 收敛为 **`16.dp`**（按 `densityScale` 缩放）。
2. **`PageHorizontal (12.dp)` 归属权**：**由页面滚动容器（`contentPadding`）唯一持有**；卡片组件（`AppManagementCard` / `LegadoMiuixCard`）默认 `outerPadding` 归零（`0.dp`）。
3. **卡外分组标题缩进 (`AppSettingSectionTitle(inCard = false)`)**：定为 **`4.dp`**（与容器 `12.dp` 叠加 = `16.dp`，与卡内文字左对齐）。

---

## 三、 统一包容器格式、存储内核与自动迁移

### 1. 单一容器协议 + `kind` 多态 + 未知字段保留
所有新包统一采用标准 ZIP 容器（或单 JSON）+ 顶层 `manifest.json`，由 `kind` 区分：
* `kind = "art"`：主美术包（日/夜色板、全套壁纸槽 `main / topBar / bottomBar / sidebar / bookInfo / panel / welcome`、字体路径，以及外挂 `iconSlotId` / `coverSlotId`）。
* `kind = "param"`：参数设置包，内部严格分三个级联区：
  * `@surfaceGlobal`（全局材质/圆角/透明度/阴影/壁纸模糊度）
  * `@surfaceScopes`（`topBar / bottomBar / bookshelfCard / dialog` 增量覆写，缺省 `null` 即继承全局）
  * `@layout`（导航骨架预设、间距密度 `densityScale`、字号缩放、页面分布列数）
* `kind = "icon_slot"` / `"cover_slot"`：独立可混搭的导航图标包与封面集合包。
* `kind = "kit"`：联合导出容器（内含引用或内联的 `art` + `param`）。
* **强制版本保护**：所有 manifest 必须包含 `schemaVersion = 1` 与 `unknownFields` round-trip 透传机制，防止低版本打开高版本包时丢失字段。

### 2. 存量包静默自动拆解迁移与顶栏入口清理
* 首次升级到新架构时，`LegacyPackageMigrator` 自动扫描用户本地已有的：
  * 旧版 `ThemePackage`（`.red` / `theme.json`）
  * 旧版 `TopBarConfig`（`topBarPackages`）
  * 旧版 `NavigationBarIconConfig`（`navigationBarPackages`）
  * 旧版 `AppearanceKit`（`appThemeKits`）
* 静默将颜色/壁纸（含顶栏壁纸）/字体拆解重组为新的 **`ArtPackage`**（并自动关联对应的 `IconSlot` / `CoverSlot`），将圆角/透明度/模糊度/布局参数提取为 **`ParamPackage`**，并保留用户当前 `PreferKey` 状态无缝衔接。
* 清理「外观中心」及「设置搜索」中独立的 `TopBarManageActivity` 入口（顶栏壁纸编辑并入主美术包编辑）。

---

## 四、 工程切片顺序（P0 → P4，可证伪、可回滚）

| 阶段 | 核心任务 | 可验证/可证伪交付标准 |
| :--- | :--- | :--- |
| **P0：基线度量与 3 项独立性能修复** | 1. 落地 `UiPerfMetrics` 基准度量日志（冷启动首帧、主界面 Tab 切换帧耗时、壁纸解码与模糊耗时、`applyUiBodyTypefaceDeep` 耗时、`recreate()` 耗时）；<br>2. **壁纸异步预解码 + 模糊 LruCache**：`ThemeConfig.getBgImage` 接入异步预热与内存缓存，`BaseActivity.upBackgroundImage` 不再在主线程同步解码与 `stackBlur`；<br>3. **消除全树字体递归**：优化 `BaseActivity.applyUiBodyTypefaceDeep` 与默认系统字体快速跳过/布局期注入；<br>4. **收敛玻璃 4 连发定时器**：用 `doOnLayout` / 布局稳定一次性监听替换 `MainActivity` `48/180/420/900ms` 轮询。 | • 命中缓存时主线程壁纸加载 `< 1ms`；<br>• 非自定义字体时 `onCreate` 字体遍历 `0ms`；<br>• Tab 切换期间无 4 连发定时采样掉帧；<br>• 零包格式改动，纯性能收益可单独度量验证。 |
| **P1：存储 IO 内核 + 空间基准定容** | 1. 落地 `PackageIoKernel`（原子写 `.tmp` + `.bak` + ZIP 安全解压）+ `PackageKindPolicy` + `PackageManageUiDelegate`（收拢 Manage Activity 的同步/导入导出交互）；<br>2. 锁定 `AppLayoutTokens` 3 项空间基准（`DetailHorizontal=16dp`、容器独占 `PageHorizontal=12dp`、卡外标题 `4dp`）。 | • 原子写异常恢复单测通过；<br>• 设置页/管理页/详情页无双重边距与标题错位。 |
| **P2：双包分离（美术包 + 参数包）与静默迁移器** | 1. 落地统一 `manifest.json`（`schemaVersion=1`, `unknownFields` round-trip）；<br>2. 切断 `updateActiveRuntimeOverride` 反写磁盘 `theme.json` 链路；<br>3. 落地 `LegacyPackageMigrator` 首次升级静默拆解本地旧包，并清理独立顶栏包入口；<br>4. 实现导入参数包时的「材质质感 (`@surface`) / 界面布局 (`@layout`)」分项勾选与脏状态提示。 | • 切换美术包时当前圆角/玻璃/布局不被覆盖；<br>• 滑块调参不改写磁盘美术包；<br>• 旧包迁移与未知字段 round-trip 单测 100% 通过。 |
| **P3：级联 Token 引擎 + 8 路 Scope 接线 + Compose 真热更** | 1. 升级 `ThemeTokenCompiler` 支持全局 + 4 大 Scope（`TOP_BAR`, `BOTTOM_BAR`, `BOOKSHELF_CARD`, `DIALOG`），配齐 JVM Golden Tests；<br>2. 完成 Compose 侧 4 个 Scope 接线（含 `AppDialogFrame` 根部显式注入 `tokens.dialog`）；<br>3. 修复 `MainActivity` 底栏圆角直读 `R.dimen` 与 `SurfaceStyles.themePanelImagePath() = null` 旁路。 | • 纯 Compose 页面（外观中心、设置页、Compose 弹窗）实时热更零 `recreate()`；<br>• 独立 Dialog Window 内 Scope 覆写 100% 生效。 |
| **P4：硬件离屏快照包裹 `recreate()` + View 侧 Dirty-Flag 局部刷新** | 1. 落地 `WindowSnapshotTransitionController`（`PixelCopy` + 输入拦截 + 首帧绘制完成门控 + 墨水屏 0ms 直切）；<br>2. 用硬件快照包裹现有 `EventBus.RECREATE`；<br>3. View 侧高频非换色微调（圆角/透明度/书架卡片 Scope）走 View Dirty-Flag 局部刷新，逐步缩小 `recreate()` 触发范围。 | • XML 页切主题/日夜无白屏、无旧主题残留；<br>• 墨水屏强制 `solid` + 0ms 直切无残影。 |

---

## 五、 三条强制工程红线（Hard Redlines）

1. **墨水屏（E-Ink）硬降级红线**：`AppConfig.isEInkMode == true` 时，在 `ThemeTokenCompiler`、`GlassSurface`、`WindowSnapshotTransitionController` 三层强制熔断：材质强切 `SOLID`、背景图与模糊清零、过渡/属性动画时长强设为 `0ms`。
2. **双回滚开关（Kill Switches）红线**：内置 `PreferKey.useLegacyTokenPipeline` 与 `PreferKey.useLegacyPackageManager`，因运行时状态真相源统一为 `PreferKey`，回滚切换时当前外观零丢失。
3. **禁止隐式继承红线**：严禁依赖 `CompositionLocal` 跨 Window 继承；独立 `Dialog`/`PopupWindow` 与 XML `RecyclerView` 必须显式调用 `tokens.resolveScope(scope)` 注入。
