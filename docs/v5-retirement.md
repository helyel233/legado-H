# V5 退役与待办台账（Retirement Ledger）

> 目的：旧栈残留、迁移遗留、技术债的统一台账。每项有状态/影响/计划期。
> 规则：**每轮 P4/P5 收尾时清一批**；新增遗留必须登记，不允许「没人管」状态存在。
> 建立日期：2026-10-12（H.1.20.0 发布后）

---

## 一、功能级遗留（影响用户，优先清）

| # | 项 | 现状 | 影响 | 计划期 |
|---|---|---|---|---|
| 1 | **AI 悬浮球失效**（`MainAiFloatingBall`） | 旧壳删除后成孤儿代码，无入口驱动 | 用悬浮球快捷 AI 的用户功能没了 | P4-6：新壳重接线（或明确宣布废弃） |
| 2 | 管理页未迁移（书源✅ / RSS源✅ / 替换规则✅ / 统一日志✅ / 字典规则✅ / TXT目录规则✅ / 书签✅ / 缓存与导出✅ / 文件管理✅ / 关于✅ / 缓存管理页✅(2026-10-12 HostCacheManageActivity：复用 CacheManageViewModel+任务刷新下沉 CacheManageTaskReloadController，旧 CacheActivity 导出编排已接 model/BookExport；我的页/详情页/WebDav通知三入口已切新壳，旧 CacheManageActivity 留作对照待退役) / 阅读记录✅(2026-10-12 HostReadRecordActivity 已含概览/热力图/最近/每日/排行/清空/启用开关，数据改走 ReadRecordRepository) / 年度报告✅(2026-10-12 HostYearlyReportActivity，复用 YearlyReportViewModel+Builder 零拷贝) / 备份✅(2026-10-13 HostBackupActivity：云存储设置/备份恢复两节全量 V5 化，流程走 BackupRestoreController；我的页「WebDav 设置」入口已切新页，旧 BackupConfigFragment 与 HostSettingsActivity 的 BACKUP_CONFIG 映射留作设置搜索回退)） | 旧 View + 主题桥 | 观感断层 | P4-3 按频率逐个 |
| 3 | 详情页弹窗设计语言旧（换源/分组/封面/看图） | ComposeDialogFragment（LegadoComposeTheme），经 V5ThemeSync 染色 | 弹窗风格与 V5 不统一 | P4-7 详情整页 V5 化 |
| 4 | 主页分源 Tab 布局 / 套件发现模式 / 发现结果内联浏览 | 迁移时降级（跳转/单一模式） | 老用户习惯的发现模式没了 | P4-8（低优先，需用户确认使用率） |
| 5 | 书源拖拽重排 | ✅(2026-10-12) 组件就位：`AppReorderableItem`/`rememberAppReorderableListState`（uikit，封装 sh.calvin.reorderable）；剩余接线：书源页手动排序模式接拖拽（需处理域名分组头跳过） | 手动排序下还不能拖 | P2+：管理页迁移时接线 |
| 6 | 统计 Tab 精简（热力图/排行/目标卡/年月选择/清空/启用开关缺） | 「我的→阅读记录」完整页仍在 | Tab 内看不到完整统计 | 与 #2 一并评估（ReadRecordActivity 迁移） |
| 6a | ✅(2026-10-12) 书架死菜单三项（搜索/书架集合/运行日志无 handler）+ 订阅 Tab 顶栏丢失收藏/源管理入口 + 规则订阅入口随菜单统一化丢失（RuleSubActivity 孤儿） | 全部已接线（书架集合/运行日志→桥接旧页；RSS 收藏/源管理→chrome 动作；规则订阅→替换规则页溢出菜单） | 已修 | 已完成 |
| 6b | 智能分组不可达（SmartGroupRuleActivity+SmartGroupEngine 全仓零引用，入口随旧书架菜单删除） | 功能整体孤儿 | 中低频 | 待裁决：补入口 or 正式退役 |
| 6c | RSS 收藏列表 G1 已接（6a）但页本身仍旧 View；主页下拉刷新/模块内加载更多缺（G6，并入 #4 P4-8）；AI 对话页 AiChatActivity 唯一入口是失效的悬浮球（并入 #1 验收标准：重接线必须恢复对话页可达） | 功能级 | 中 | 随 #1/#4 落地 |
| 7 | ✅(2026-10-12) 设置主题变更即时生效 | 设置 Fragment 全部 Compose（`rememberThemeUiPalette` 自响应偏好变化）；`ConfigActivity` 的 RECREATE 订阅已移除（免重建刷新，手册 0.3） | 已修 | 已完成 |
| 8 | ✅(2026-10-12) 我的-帮助按钮空实现 | chrome action 已接 `showHelp("appHelp")`（经 helpRevision 桥） | 已修 | 已完成 |

## 二、国际化债务

| # | 项 | 现状 | 计划期 |
|---|---|---|---|
| 9 | ✅(2026-10-12) 用户可见页面硬编码中文全部抽取（151 条 v5_ 资源）；剩余：包中心/包编辑器/导入器/HostTopBar/HostSideBar 约 40 条低频文案未抽（本轮已顺手清「排序方式」标题） | 主流程已无硬编码 | P5-i18n 低频件 |
| 10 | ✅(2026-10-12) 151 条 v5_ 资源已补全语言变体译文：base（英文，原为中文属缺陷已纠正）+ zh / zh-rTW / zh-rHK / ja-rJP / es-rES / pt-rBR / vi | 全部语言目录均有 v5_ 条目；译文为人工精翻质量，建议后续母语者抽查 | 人工抽查（低优） |
| 11 | debug 面板/组件博物馆/占位演示文案 | 有意不抽（调试件） | 不发版，不处理 |

## 三、工程与质量

| # | 项 | 现状 | 计划期 |
|---|---|---|---|
| 12 | V5 页面单测几乎为零 | 仅 token 层 45 项 | P5-3：DiscoverEngine 分类、LegacyAppearanceImporter 映射、V5PackageStore round-trip、DestinationRegistry 补 JVM 单测 |
| 13 | V5ThemeSync 无测试锁行为 | 隐式双写（V5→旧键） | P5-3 同批 |
| 14 | debug 面板无「页面直达」 | 进深层页面（书源管理）要 5 步，测试成本高 | P5-3 小件 |
| 15 | 状态恢复（当前 Tab/搜索/选择态） | remember 无 Saveable | P5-3 |
| 16 | 无障碍（⋮ 无 contentDescription、自绘 chips 触控区 <48dp） | ⏳(2026-10-12) 部分修复：`AppOverflowMenuButton` 已加 contentDescription + 48dp 触控区，`AppSelectionBar` 项 ≥44dp；剩余：AppChip 触控区、各页 Icon 扫描 | 部分修复 | P5-4 全量扫 |
| 17 | Android 16 兼容清单（后台启动/预测性返回/edge-to-edge） | 仅 NEW_TASK 踩过 | P5-4 统一过清单 |

## 四、旧栈残留（可物理删除，待排期）

| # | 项 | 规模 | 删除前置 |
|---|---|---|---|
| 18 | 旧 `BookSourceActivity`（View 壳） | 1323 行 | #2 完成 + 确认无引用 |
| 19 | `MainPageActivity` + 旧主 Fragment 链（Homepage/Rss/Explore/My/BaseBookshelf） | 数千行 | 旧 UI 设置预览路由废弃后 |
| 20 | `res/xml/pref_config_*.xml` 12 个 | 现仅旧设置搜索索引用 | #2 设置内容 V5 化后 |
| 21 | `RssSortActivity`（旧订阅排序页） | 578 行 | 确认旧壳无入口后（RssSearchActivity 仍在用？） |
| 22 | `ThemeConfig`/`ThemeApplier`/`AppColorProvider`/`UiKitBridge`/`ThemePackageManager` 等旧取值栈 | 数千行 | P4 全部页面迁完、V5ThemeSync 可删时 |
| 23 | `EventBus.RECREATE` 换肤路径 | 散布 | 同上 |

## 五、文档债

| # | 项 | 计划期 |
|---|---|---|
| 24 | `docs/ui-v5-destinations.md` 分级表与实际迁移进度漂移 | 每轮迁移后更新 |
| 25 | `docs/ui-v5-impl.md` 文件锚点为 2026-10-11 现状，已漂移 | P4 收尾时重校 |
| 26 | V5 交互规范一页纸（溢出菜单/选择模式/危险操作/长按提示约定） | P5-2 |
| 27 | 会话状态下沉（2026-10-12 全量扫描结论：旧 UI 独占消费 service/model 事件）。#1 书源校验 ✅ 已下沉 `model/CheckSourceSession`（服务写、UI collect，keepScreenOn 泄漏已修）；#2 Web服务/自动任务运行态 ✅(2026-10-12 `eventObservableAsState` 通用助手 + HostMyContent 接线)；#3 缓存/导出 4 事件 ✅(2026-10-12 同助手，HostCacheActivity 接 EXPORT_BOOK/UP_DOWNLOAD/SAVE_CONTENT)；#4 书架"单项更新中"状态新书架缺等价物；#5 ExploreFragment 私有 Suite* 预取会话（迁发现页前必须抽 DiscoverSuiteEngine）；死事件 NOTIFY_MAIN / REVIEW_CACHE_SAVED / UP_CONFIG 无消费者可清 | 按各功能迁移期落地 |
| 28 | **V5 框架 P0 缺口**（2026-10-12 组件审计）：①导航返回栈 ✅(2026-10-12 HostState 栈+传参+BackHandler+Tab 清栈语义)；③ManageListScaffold 核心半件 ✅（AppSelectionState + HostV5Page selectionCount 接管标题/返回，RSS 源管理迁移已验证）；②AppWebViewScreen 待做（RSS 阅读/源登录显式跳旧体系）。✅ 前批已补：AppOptionRow/AppTextField 扩展/AppFormDialog/AppProgressDialog/Snackbar+Overlay/AppFilePicker | ②随 RSS/登录迁移清（并入 #30） |
| 29 | **1.7.3 全功能盘点：逻辑内嵌 UI 层案例**（2026-10-12 二次扫描，B1~B16）：<br>B1 WebDav 任务队列整个类住 ui 包（`ui/book/cache/WebDavTaskManager.kt`）→ 迁 model；<br>~~B2/B3 导出编排+进度双份拷贝~~ ✅(2026-10-12) 编排已下沉 `model/BookExport`（双壳接线，进度改 StateFlow 会话仍待做）；<br>~~B4/B5 书源批量操作双份拷贝~~ **勘误**(2026-10-12)：两壳已共用 BookSourceViewModel（数据层无拷贝），仅剩在线导入历史 ACache 胶水 ~20 行重复，降级低优；<br>B6 RuleSub 增删改直连 appDb 无 VM（`ui/rss/subscription/RuleSubActivity:157-260`）→ 迁移发现/订阅域前置；<br>B7 备份/恢复编排 ✅(2026-10-12 抽 help/storage/BackupRestoreController：startBackup(含进度写 BackupProgressHolder+全局协程)、S3满回退判定、ensureCloudStorageForRestore(返回类型由壳刷新)、listCloudBackups/listCloudAssetNames/restoreFromCloud、findLocalAssetsUris/restoreFromLocal、配置完备性判定；BackupConfigFragment 已改接控制器，MainViewModel.restoreWebDav 同走一条恢复链)。必须留在 UI：ActivityResult 契约、进度/确认弹窗（含宿主重建后重建）、权限申请、设置树渲染；<br>B8 新壳统计/阅读记录页直连 appDb ✅(2026-10-12 建 data/repository/ReadRecordRepository，HostStatsContent + HostReadRecordActivity 共用同一数据汇聚)；<br>B9 WebService/AutoTask 开关+态刷新在 Composable（HostMyContent）→ 抽 ServiceToggleController；<br>B10 源登录会话在 Dialog（`SourceLoginDialog` 35KB）；<br>B11 字体扫描/权限在 Dialog（`FontSelectDialog:93-208`）→ 抽 FontScanner；<br>B12 VideoPlayerActivity:886 UI 直接 startForegroundService；<br>B13 发现套件引擎抽取（即 #27-5，迁发现页前置）⏳(2026-10-13 第一步完成)：运行时状态类/常量/签名扩展（SuiteRandomDeck、SuitePreparedBatch、SuiteHorizontalPagingState、SuiteRankedPagingState、DiscoverySuitePageSnapshot(+Store)、DiscoveryCacheWriteScope、cacheSignature/deckSignature/分页签名/deckKey/suiteDeckKey）已从 ExploreFragment 的 file-private 搬成同包 internal 的 DiscoverSuiteRuntime.kt（零行为变更），并合并了 DiscoverySuiteHomeScreen 里重复的 deckKey。**下一步**：把 47 个套件方法（加载编排 469、随机牌堆填充 800、唯一网络出口 loadSuiteTargetPage 863、刷新 935、预取批次 1007、横排/排行加载更多 1044/1095、快照读写 601-711）搬进 DiscoverSuiteEngine，引擎需接收外部 CoroutineScope 并暴露 cancelAll()（风险点：三个 Semaphore 归属、@Volatile 跨线程字段、isCurrentSuiteWidget 陈旧写回守卫、调用时序、WebViewPool.Scope.DISCOVERY 生命周期）；ExploreFragment(158KB)+DiscoverySuiteManage(76KB)ngine）；<br>B14 阅读域巨类集群 ReadBookActivity(266KB)+20+大Dialog（分阶段：先朗读/配置后端化）；<br>B15 阅读记录统计+Widget 数据源住 ui 包 ✅(2026-10-12 ReadRecordWidgetStore 连同 ReadRecordRankItem/ReadRecentVisual*/ReadRecordGoalConfig 已迁 help/readrecord，旧页与新壳共用)；<br>B16 ThemeManageActivity(111KB) 主题包管理全量业务在 Activity<br>⚠️ 模式教训：**新壳迁移禁止复制旧 UI 逻辑，必须先抽引擎/控制器再写壳**（B2/B3、B4/B5 双份拷贝已在发生） | 逻辑写死界面，双份拷贝漂移风险 | 按域迁移期落地；B2/B5 双拷贝优先 |
| 29a | **新壳完全无法触达的功能**（旧入口随 MainActivity 删除断链）：BookCacheExportActivity（单书导出二级页）、RssFavoritesActivity（RSS 收藏，已由 6a 接线恢复）、DiscoverySuiteManageActivity、HttpTts 管理链（HttpTtsEditDialog/SpeakEngineDialog/ReadAloudConfigDialog，仅旧阅读页可达）、应用内 WebViewActivity（HostMyContent 只提供复制 URL/系统浏览器）；另有旧 SearchActivity/CacheActivity/AllBookmark 等已有 Host 等价页属死路正常 | 单书导出/HttpTTS 编辑/套件管理三个真缺口 | 随对应域迁移清 |
| 30 | **V5 组件缺口清单**（2026-10-12 审计，P0=马上要用）。P0：①HostV5EditPage ✅(2026-10-12 HostV5Page.kt，dirty+返回确认+保存动作) ②AppMultiFilterDialog ✅(2026-10-12，Set&lt;Int&gt; 回传) ③AppAsyncImage ✅(2026-10-12 app 侧 ui/host/AppAsyncImage.kt，移植 Legado_Max AppImage：Glide Drawable 链路+按尺寸降采样+动图 Painter) ④AppWebView ✅(2026-10-12 基础容器：state/进度/标题/URL/错误回调，JS 注入走 factory)。P1 已补：AppSlider 增强(steps/valueLabel/onValueChangeFinished)、AppNumberField、AppNumberPickerDialog、AppCollapsibleGroup、AppTaskRow、AppPullRefresh(M3 薄壳)、Modifier.appMinTouchTarget、AppFilled/GhostButton 补 enabled 态；2026-10-12 二批（参考 legado-with-MD3 分支）：AppFastScroller（移植 VerticalFastScroller/komikku，粘性头 key 用 APP_STICKY_HEADER_KEY_PREFIX）、AppSwipeActions(侧滑操作+无障碍+触觉)、AppVerticalSlider(旋转实现)、AppTimePickerDialog(HH:mm)、AppDropdownField 待做。P1 已补（2026-10-12 三批，MD3 移植收尾）：AppDropdownField（表单下拉字段，带标签/错误态）、AppColorField + AppColorPickerDialog（自绘 HSV 取色，ARGB Int 进出，未引三方色板库）。P1 剩余：AppPathField、AppCodeField、AppPhotoViewer、AppDetailSeekBar、AppListItem onLongClick（均为按需再补，当前页面无手搓对应物）。P2：日期选择（时间已补）、AppStickyHeader、内置目录浏览。<br>阅读界面为最大缺口：仅 ReaderBridge 契约，翻页体系(PageView/ContentTextView/8种PageDelegate/放大镜)必须 AndroidView 互操作。参考库：Legado_Max_compare 的 ui/widget/components/（设置行/对话框套件可后续白捡：AppSearchableChoiceDialog、AppAdaptiveDialog、SwipeActionContainer、FlexWrapLayout、CategoryTabs 等） | 管理列表/文件选择已够用；编辑表单/WebView/图片缺件已清 | 剩余 P1 随对应页面迁移补 |
| 31 | **已迁移页面复核结论**（2026-10-12）：方向正确不需重做（搜索/我的/书架/发现/书源管理架构对）；~~HostCacheActivity 导出编排去拷贝~~ ✅(2026-10-12 已接 model/BookExport)；书源页批量操作确认已共用 ViewModel 无需改；前置约束：发现页 V5 化前必须先抽 DiscoverSuiteEngine（B13） | 防止拷贝债固化 | 剩余项随各域迁移落地 |
| 32 | **手搓 UI → 组件替换**（2026-10-12 扫描约 20 处，已换 9 处）：AppInputDialog ×4（HostShelfSheets 分组命名/添加网址、HostHomeManageSheet 命名、HostRssSort 源内搜索，各删 ~40 行手搓弹窗）；AppSlider valueLabel（HostPackageEditors SliderRow 删文案拼装）；AppEmpty（HostHomeContent 暂无内容）；AppFastScroller 全量 ✅（日志×2、书源、缓存管理、RSS源管理×2、替换规则、文件管理、TXT目录、字典、书架列表模式、RSS排序、书签，共 11 处长列表）。剩余：AppSwipeActions 属新增能力（文件管理/书签滑删）需产品确认后再接 | 统一观感、删重复代码 | 替换批次已完成 |

---

**维护规则**：每完成一项 → 标注 ✅日期 并移到文末「已完成」区；新增遗留 → 登记到对应表。
本台账与 `docs/v5-migration-gate.md`（验收门）配套：门防止新遗留，账册跟踪旧遗留。
