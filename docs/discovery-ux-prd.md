# 发现页使用体验优化 PRD

> 项目：LegadoH（开源阅读类 Android 应用）
> 范围：发现页（Explore）以「经典模式」为主，兼顾经典 / 现代 / 套件三形态的衔接与切换体验
> 角色：产品经理 · 许清楚
> 性质：产品需求文档（不涉及实现）

---

## 1. 项目信息

- **Language**：中文
- **Project Name**：`discovery_ux_optimization`
- **原始需求复述**：优化发现页使用体验——重点提升经典模式（旧书源经典列表）的滚动流畅度与稳定性；同时改善经典 / 现代 / 套件三形态之间的切换无感度、视觉一致性与异常降级体验。

## 2. 产品目标（可量化验收）

| 编号 | 目标 | 量化验收标准 |
| --- | --- | --- |
| G1 | 经典模式滚动流畅、无主线程卡顿 | 经典列表（`rvFind`）在 60Hz 设备上滚动丢帧率 ≤ 5%；展开/收起书源行时无 >100ms 的主线程阻塞（FrameTiming Jank） |
| G2 | 三形态切换「无感、有据、可逆」 | 切换形态时首帧可见 ≤ 300ms；切换后回到上一形态能恢复浏览位置（经典模式的滚动位置与展开源） |
| G3 | 异常降级体验统一、可理解 | 书源失效 / 网络失败 / 无数据 / 超时四类异常，均有明确的非技术性文案与重试入口，不再向用户暴露原始错误信息 |
| G4 | 与既有缓存 / 限流 / 布局配置 / 设置搜索无缝衔接 | 新增体验改动不破坏 `DiscoverySuiteStore` 解析缓存、3 个 Semaphore 限流、`DiscoveryCachePolicy` 512KB 上限与 `UP_PAGE_LAYOUT` / 设置搜索命中能力 |

## 3. 用户故事

### US1（经典模式老用户）
> 作为**使用经典发现页多年的老用户**，我希望列表在书源很多（数十～上百个）时滚动依旧顺滑、展开分类不卡顿，这样我才能高效地浏览和切换书源。

**验收标准**：滚动流畅（G1）；展开书源分类时行内控件不闪烁、不错行、不重复加载；下拉刷新后列表位置与展开状态保持合理。

### US2（在三种形态间切换的用户）
> 作为**在经典 / 现代 / 套件之间切换的用户**，我希望切换入口清晰、切换后视觉过渡自然，且切回来时能回到我上次停留的位置，而不是每次从头开始。

**验收标准**：切换即时生效（≤300ms 首屏）；经典模式离开/返回能恢复 `classicLastScrollPosition` 与 `classicExpandedSourceUrl`；切换过程中不出现空白闪烁或残留上一形态的控件。

### US3（遭遇书源异常的用户）
> 作为**遇到某个书源失效 / 网络失败 / 无数据的用户**，我希望看到「哪里出问题、可以怎么办」的友好提示，而不是一串报错或永远的加载圈。

**验收标准**：书源解析失败（`exploreKinds()` 返回 `ERROR:` 条目）、网络失败、空数据、加载超时分别有独立且非技术性的降级文案与「重试」入口；不再直接展示 `e.localizedMessage`。

### US4（配置套件的用户）
> 作为**自定义发现套件的用户**，我希望首屏加载快、切换套件不白屏、单个控件加载失败不影响整体，这样我的个性化首页始终可用。

**验收标准**：套件切换复用快照与签名比对（`cacheSignature`）不重复请求；单个 widget 失败仅该 widget 降级；全页不被单个慢源拖死（有超时上限）。

### US5（通过搜索找设置的用户）
> 作为**习惯用设置搜索的用户**，我希望搜索「发现页」「布局」「瀑布流」「套件」等词时能直达对应设置项，改完立刻在发现页生效。

**验收标准**：`PageLayoutConfigFragment` 的 `discoveryPageLayout` 与 `DiscoverySubscriptionConfigFragment` 的 `discoveryPageMode` 均可被 `SettingsSearchIndex` 命中；修改后通过 `EventBus` 即时生效。

## 4. 需求池

### P0（必须做）

#### P0-1 经典模式主线程卡顿治理
- **现状痛点**：`ExploreAdapter.convert()` 每次绑行都会执行 `ExploreGlassBackdrop.rowBackground()`（含 `FrostedGlassDrawable.draw()` 里每帧 `getLocationOnScreen` + `BitmapShader` 采样）与 padding 计算；`loadExploreKinds()` 触发 `BookSource.exploreKinds()`，其中对 `@js:` / `<js>` 类型走 `runScriptWithContext` + `evalJS`，虽有 `Mutex` 与 `ConcurrentHashMap` 缓存，但绑定/展开时仍需在可见路径上做重活。书源行内 `Type.url/button/text/toggle/select` 的 `viewName` 动态文案通过 `Coroutine.async(scope, IO)` 异步求值，但结果回填与行复用校验逻辑复杂，存在错行/重复求值风险。
- **目标**：经典列表滚动帧率达标（G1），展开行无主线程 >100ms 阻塞。
- **建议方案**：将行绑定中的重活（毛玻璃背景构建、`exploreKinds` 求值、动态 `viewName` 求值）统一走 IO 且对同一源做「在途去重」，主线程仅做轻量 diff 与状态回填；`FrostedGlassDrawable` 的逐帧 `getLocationOnScreen` 改为布局/滚动回调驱动的位置缓存，仅在位置变化时重算 shader matrix。
- **验收标准**：滚动 Jank ≤5%；展开行在无 JS 缓存时仍不卡顿；`convert`/`draw` 路径无可疑长任务。
- **涉及文件/模块**：`ExploreAdapter.kt`、`ExploreGlassBackdrop.kt`、`help/source/BookSourceExtensions.kt`。

#### P0-2 经典模式异常降级与重试
- **现状痛点**：`upExploreData()` 中 `binding.tvEmptyMsg.isGone = it.isNotEmpty() || query非空`，空态仅当列表为空且无搜索词时出现，未区分「无书源」与「书源全部失效」；`ExploreAdapter.upKindList()` 对 `kinds.isEmpty()` 仅显示 `R.string.explore_kinds_empty`，无重试；`refreshExplore()` 失败无用户可见反馈。
- **目标**：书源失效 / 网络失败 / 无数据 / 超时四类异常均有可理解文案与重试入口（G3）。
- **建议方案**：为经典列表建立轻量「加载状态机」（成功 / 空 / 失败），失败态提供「重试」按钮与「去书源管理」快捷入口；`exploreKinds()` 返回 `ERROR:` 前缀条目时，在行内展示可点击的错误摘要而非展开原始 stacktrace；`refreshExplore()` 失败时 Toast/行内提示。
- **验收标准**：四种异常均有非技术文案与重试；`ERROR:` 条目可点击查看摘要、不再直接展示堆栈；下拉刷新失败有反馈。
- **涉及文件/模块**：`ExploreFragment.kt`（`upExploreData` / `tvEmptyMsg`）、`ExploreAdapter.kt`（`upKindList` / `refreshExplore`）、`BookSourceExtensions.kt`（`exploreKinds` 错误条目构造）。

#### P0-3 现代模式原始错误信息泄露治理
- **现状痛点**：`loadDiscoverBooks()` 的 `catch` 分支 `binding.tvDiscoverEmpty.text = e.localizedMessage ?: unknown_error`（`ExploreFragment.kt` L3577）直接向用户展示技术性异常文案；套件控件加载失败被 `AppLog.put` 吞掉后仅表现为空 widget（`loadSuiteWidgetBooks` / `loadSuiteRankedListWidgetBooks` 的 `catch(Throwable)`），用户无法区分「源挂了」与「没数据」。
- **目标**：统一为「友好文案 + 可重试」降级（G3）。
- **建议方案**：现代 / 套件加载失败统一走 `Exception → 用户文案` 映射层（网络失败 / 超时 / 源失效 / 未知），现代模式空态展示文案 + 重试；套件单个 widget 失败展示「该模块加载失败，点击重试」的占位卡片。
- **验收标准**：任何异常不再出现 `localizedMessage` / 技术堆栈；重试能重新触发该 widget / 列表加载。
- **涉及文件/模块**：`ExploreFragment.kt`（`loadDiscoverBooks` / `loadSuiteWidgetBooks` / `loadSuiteRankedListWidgetBooks` / `loadSuiteTargetPage`）、`DiscoverySuiteHomeScreen.kt`（widget 降级占位）。

#### P0-4 套件 / 现代加载超时兜底
- **现状痛点**：widget 实际数据加载 `loadSuiteTargetPage()` → `WebBook.exploreBookAwait()` 无 `withTimeoutOrNull`，慢源 / 断网可能长期占用 `suiteTargetLoadSemaphore`（`SUITE_TARGET_LOAD_PARALLELISM=4`）与 `suiteWidgetLoadSemaphore`（=4）的许可，进而拖累其余 widget 首屏；现代列表 `WebBook.exploreBookAwait` 同样无单页超时上限（仅依赖外层 Job 取消）。
- **目标**：单源 / 单页加载有上限，不饿死并发许可（G4）。
- **建议方案**：为 `WebBook.exploreBookAwait` 的发现页调用统一加可配置超时（如 `SUITE_TARGET_LOAD_TIMEOUT_MS`），超时按「失败」处理并释放 Semaphore 许可；现代列表单页加载超时进入可重试失败态。
- **验收标准**：单个慢源不会让其它 widget 无限等待；超时后有降级反馈；并发许可（Semaphore）始终能归还。
- **涉及文件/模块**：`ExploreFragment.kt`（`loadSuiteTargetPage` / `loadDiscoverBooks` / companion 常量区）。

### P1（应该做）

#### P1-1 三形态切换无感化与视觉一致性
- **现状痛点**：`applyDiscoveryMode()` 通过 `binding.titleBar.isGone` / `binding.llModernDiscovery.isVisible` / `binding.composeDiscoverySuite.isVisible` / `binding.rvFind.isGone` 瞬时切换（`ExploreFragment.kt` L373-378），无过渡；三种形态顶栏实现不同（经典用 `MainTopBarView.Mode.DISCOVERY`，现代用 `topBar` + `updateModernTopBarOverlay()` 毛玻璃，套件用 `DiscoverySuiteSearchBar`），视觉语言不统一。
- **目标**：切换有轻过渡、三形态视觉基调一致（G2）。
- **建议方案**：为形态切换增加轻量交叉淡入（fade）过渡；抽统一「发现页顶栏」规范（搜索入口、形态切换入口、更多菜单的图标/位置一致）；明确切换入口固定在顶栏同一位，避免入口因形态不同漂移。
- **验收标准**：切换无空白闪烁、无上一形态控件残留；三形态的搜索入口与切换入口位置一致；过渡时长 ≤300ms。
- **涉及文件/模块**：`ExploreFragment.kt`（`applyDiscoveryMode`）、`DiscoverySuiteHomeScreen.kt`（`DiscoverySuiteSearchBar`）、`ExploreModernListScreen.kt`。

#### P1-2 切换往返状态保持（浏览位置 / 展开状态 / 选中项）
- **现状痛点**：经典模式已通过进程级变量 `classicLastScrollPosition` / `classicExpandedSourceUrl` 在 `classicRememberViewPosition()` + `setPendingViewRestore` 恢复（较好）；但现代模式离开时 `stopModernMode()` 直接清空 `discoverTagItems` / `discoverBooks`（仅靠 `modernDiscoverResultCache` 落盘缓存兜底），套件模式 `stopSuiteMode()` 保存快照后 `clearSuiteRuntimeState()`。三形态间往返时，现代/套件的「选中书源 / 选中套件 / 浏览位置」恢复不完整。
- **目标**：三形态各自离开→返回都能恢复关键上下文（G2）。
- **建议方案**：统一「形态状态快照」模型（选中源 / 选中套件 / 滚动位置 / 展开项），离开时快照、返回时恢复；复用已有 `DiscoverySuitePageSnapshot` / `DiscoverySuitePageSnapshotStore` 思路扩展到现代模式。
- **验收标准**：现代模式返回恢复选中书源与滚动位置；套件返回恢复选中套件与滚动位置；重启应用至少恢复套件快照与经典位置不落盘的既定行为不变。
- **涉及文件/模块**：`ExploreFragment.kt`（`stopModernMode` / `stopSuiteMode` / `classicRememberViewPosition` / `DiscoverySuitePageSnapshotStore`）。

#### P1-3 经典模式数据流延迟与抖动优化
- **现状痛点**：`upExploreData()` 的 `collect` 中每次发射后 `delay(DISCOVERY_CLASSIC_FLOW_COALESCE_DELAY_MS = 120L)`，用于合并高频数据库变更；该固定 120ms 延迟在数据库连续变更时会累积，造成列表更新「慢半拍」；且与 `flowWithLifecycleAndDatabaseChange` + `conflate()` 组合后，最终落地时机不直观。
- **目标**：列表更新及时且不因高频变更抖动（G1）。
- **建议方案**：将「每发射固定 delay」改为「可取消的 debounce」语义（合并窗口内多次变更只落地一次，窗口结束立即落地），缩短主观延迟；确认 `conflate()` 与 `distinctUntilChanged` 的位置保证最终一致。
- **验收标准**：批量增删书源后列表在合并窗口内一次到位；快速连续操作无中间闪跳；`DISCOVERY_CLASSIC_FLOW_COALESCE_DELAY_MS` 的可感知延迟下降。
- **涉及文件/模块**：`ExploreFragment.kt`（`upExploreData` / `initGroupData`）。

#### P1-4 套件首屏快照命中与「先快照后刷新」体验
- **现状痛点**：套件首屏 `loadSelectedSuiteWidgets()` 先尝试 `restoreSuiteSnapshot()` 复用快照，否则才网络加载；但若快照未命中，用户看到的是空壳 + 逐个 widget 加载圈，缺乏整体骨架屏；配置变更（`signature` 变化）时旧快照被整体丢弃，无渐进降级。
- **目标**：首屏有稳定骨架，命中快照秒开（G2）。
- **建议方案**：快照未命中时展示与 widget 结构对应的骨架占位（skeleton）；签名变化但目标源仍有效时，优先复用「同源旧数据」做临时展示并标记「刷新中」，避免整页空白。
- **验收标准**：首屏骨架屏 ≤1 帧内出现；快照命中时首屏可见 ≤300ms；配置微调不整页清空。
- **涉及文件/模块**：`DiscoverySuiteHomeScreen.kt`、`ExploreFragment.kt`（`loadSelectedSuiteWidgets` / `applySuiteSnapshot`）。

### P2（可以做）

#### P2-1 经典模式毛玻璃性能分级
- **现状痛点**：`ExploreGlassBackdrop` 已把重解码+软件模糊放 IO 且按 key 去重（`inFlightKeys` / `pendingCallbacks`），质量较好；但低端机 / 墨水屏下仍有逐行 `FrostedGlassDrawable` 绘制开销（`isEInkMode` 已强制 level=0）。
- **目标**：低端设备自动降级（G1）。
- **建议方案**：按设备性能档 / 用户手动档位自动下调 `themeExploreGlassBlur` 生效强度或退化为纯实底；为「毛玻璃强度」提供「自动」档。
- **验收标准**：低端机 / 墨水屏不因毛玻璃明显掉帧；用户可感知强度变化可平滑过渡。
- **涉及文件/模块**：`ExploreGlassBackdrop.kt`、主题设置页 `themeExploreGlassBlur`。

#### P2-2 发现页布局设置与形态设置深度衔接
- **现状痛点**：`discoveryPageLayout`（列表/瀑布流/网格，`PageLayoutConfigFragment`）与 `discoveryPageMode`（经典/现代/套件，`DiscoverySubscriptionConfigFragment`）分属两个二级页，用户对「布局」与「形态」关系容易混淆；`discoveryPageLayout` 仅对现代模式生效，经典模式无布局维度。
- **目标**：配置入口语义清晰、命中搜索（G4）。
- **建议方案**：在两处设置项 summary 中互链说明「布局仅在现代模式生效 / 形态切换入口」；补充 `searchKeys` 别名（如「套件」「形态」「发现页样式」）确保 `SettingsSearchIndex` 命中；考虑在发现页顶栏提供直达「页面布局」的快捷入口。
- **验收标准**：搜索「瀑布流 / 套件 / 形态 / 发现页」能命中对应项；两处 summary 互相说明适用范围。
- **涉及文件/模块**：`PageLayoutConfigFragment.kt`、`DiscoverySubscriptionConfigFragment.kt`、`SettingsSearchIndex.kt`。

#### P2-3 套件编辑器操作成本优化（非本轮核心）
- **现状痛点**：`DiscoverySuiteManageActivity.kt`（约 1935 行）承载套件/控件/目标三层编辑，新建控件、选源、选分类链路长。
- **目标**：减少「建套件→可用」的操作步数。
- **建议方案**：提供「从当前书源快速新建控件」「套件模板 / 一键随机」等快捷入口；此为后续迭代项。
- **验收标准**：新建一个可用套件的平均操作步数下降。
- **涉及文件/模块**：`DiscoverySuiteManageActivity.kt`、`DiscoverySuiteConfig.kt`。

## 5. UI / 交互说明

### 5.1 顶栏与形态切换
- 三形态统一顶栏规范：左侧为形态/套件切换入口，中间为搜索入口，右侧为「更多」（经典分组、布局、管理）。
- 形态切换采用 ≤300ms 交叉淡入；切换过程中禁用重复点击（防抖）。

### 5.2 经典模式
- 列表保持「书源名行 → 展开分类控件行」结构；展开行仍沿用锚定 `bookSourceUrl`（`expandedSourceUrl` / `reanchorExpanded`）避免错行。
- 空态 / 失败态：
  - 无书源：引导「去书源管理」。
  - 全部失效：展示「书源暂不可用」+「重试」。
  - 网络失败：展示「网络异常」+「重试」。
- 展开分类为空：行内展示「暂无分类，下拉刷新重试」并保留展开态可重试。

### 5.3 现代模式
- 列表 / 瀑布流 / 网格三布局由 `discoveryPageLayout` 控制；顶栏毛玻璃在 API≥33 生效，低版本退化为内边距下移（现状保留）。
- 加载失败展示非技术文案 + 重试；分页触底加载（`shouldLoadMore` 阈值 4/7）保持。

### 5.4 套件模式
- 首屏：快照命中秒开；未命中展示骨架屏；每个 widget 独立加载态。
- 单个 widget 失败：该 widget 显示「加载失败，点击重试」占位，不影响其它 widget。
- 套件切换：复用 `signature` 判断不重复请求，切换后滚动位置恢复到顶部或保持（待确认）。

## 6. 待确认问题（需客户拍板）

1. **形态切换入口的最终位置与形态**：切换入口放在顶栏左侧、还是「更多」菜单内？是否需要「长按发现页 Tab 快速切换形态」这类快捷手势？
2. **异常文案的措辞基调**：是否需要区分「书源失效」与「网络失败」的措辞，还是统一为「加载失败，点击重试」？
3. **加载超时时长**：单页/单 widget 的合理超时上限（如 5s / 8s / 10s）与「慢但能出结果」之间的取舍由谁定？
4. **切换往返状态保持的范围**：现代 / 套件的浏览位置与选中项是否也要像经典模式一样跨进程（重启后仍恢复）？目前经典模式明确「不落盘、重启从头」——是否三形态统一为「仅进程内保持」？
5. **经典模式是否也需要布局维度**：现有 `discoveryPageLayout`（列表/瀑布流/网格）仅作用于现代模式，经典模式是否需要提供「分组折叠 / 紧凑 / 瀑布流」等可选布局？
6. **毛玻璃「自动」档位**：是否接受按设备性能自动降级毛玻璃强度（可能牺牲部分视觉一致性换取流畅）？
7. **错误详情入口**：书源解析失败的 `ERROR:` 详情，是否保留一个「高级用户可见」的折叠入口（如长按查看技术详情），还是彻底对普通用户隐藏？
8. **套件编辑器的快捷建套件**：P2-3 的「模板 / 一键随机」是否纳入本轮范围，还是完全后续迭代？

---

### 附：关键代码佐证索引

| 结论 | 位置 |
| --- | --- |
| 三形态同驻单 Fragment、瞬时切换 | `ExploreFragment.kt` `applyDiscoveryMode()` L366-402，`binding.titleBar.isGone` 等 L373-378 |
| 经典模式展开锚定与视图恢复 | `ExploreAdapter.kt` `expandedSourceUrl` L80、`reanchorExpanded()` L663、`setPendingViewRestore()` L683 |
| 经典模式毛玻璃逐行绑定 | `ExploreAdapter.convert()` L101-104 → `ExploreGlassBackdrop.rowBackground()`；`FrostedGlassDrawable.draw()` L296-329 每帧 `getLocationOnScreen` |
| 经典模式 JS 求值与缓存 | `BookSourceExtensions.exploreKinds()` L58-127（`Mutex` + `runScriptWithContext`）；`ExploreAdapter.evalUiJs()` L535 |
| 经典模式数据流 120ms 延迟 | `ExploreFragment.upExploreData()` L3625-3654，`DISCOVERY_CLASSIC_FLOW_COALESCE_DELAY_MS = 120L` L4082 |
| 现代模式原始错误泄露 | `ExploreFragment.loadDiscoverBooks()` catch L3577 `binding.tvDiscoverEmpty.text = e.localizedMessage` |
| 套件 / 现代无超时 | `loadSuiteTargetPage()` L936-956 无 `withTimeoutOrNull`；仅封面预载有 `SUITE_VISIBLE_COVER_PRELOAD_TIMEOUT_MS` L3939 |
| 三 Semaphore 限流 | `ExploreFragment` L228-230（widget/target/cover 各 4）与 companion 常量 L3920-3923 |
| 套件配置解析缓存 | `DiscoverySuiteConfig.kt` `DiscoverySuiteStore.load()` L86-103（`cachedRawKey` / `cachedParsed`） |
| 缓存策略 512KB 上限 | `DiscoveryCachePolicy.kt` `MAX_SQLITE_VALUE_BYTES` L16 |
| 布局配置与设置搜索衔接 | `PageLayoutConfigFragment.kt` L38-52、`DiscoverySubscriptionConfigFragment.kt` L27-48、`SettingsSearchIndex.kt` L82-85 |
