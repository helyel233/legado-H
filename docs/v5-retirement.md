# V5 退役与待办台账（Retirement Ledger）

> 目的：旧栈残留、迁移遗留、技术债的统一台账。每项有状态/影响/计划期。
> 规则：**每轮 P4/P5 收尾时清一批**；新增遗留必须登记，不允许「没人管」状态存在。
> 建立日期：2026-10-12（H.1.20.0 发布后）

---

## 一、功能级遗留（影响用户，优先清）

| # | 项 | 现状 | 影响 | 计划期 |
|---|---|---|---|---|
| 1 | **AI 悬浮球失效**（`MainAiFloatingBall`） | 旧壳删除后成孤儿代码，无入口驱动 | 用悬浮球快捷 AI 的用户功能没了 | P4-6：新壳重接线（或明确宣布废弃） |
| 2 | 管理页未迁移（~~全部 11 项~~ ✅ 全部完成 2026-10-13；含设置页：P5-1 渲染单点切换 ✅(2026-10-13 SettingSpecScreen 四类行(开关/选项/动作/滑条)+分区换 V5 组件，ComposeSettingFragment 包 HostV5ThemeRoot，Spec 契约/搜索定位/菜单桥不变，10 个分区页全局生效；第二切片待做：各 Fragment 内直用的 Compose 输入弹窗 ~26 处换 AppInputDialog/FormDialog/MultiFilterDialog)） | 旧 View + 主题桥 | 观感断层 | 已完成（弹窗二批收尾） |
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
| 29 | **1.7.3 全功能盘点：逻辑内嵌 UI 层案例**（2026-10-12 二次扫描，B1~B16）：<br>B1 WebDav 任务队列整个类住 ui 包（`ui/book/cache/WebDavTaskManager.kt`）→ 迁 model；<br>~~B2/B3 导出编排+进度双份拷贝~~ ✅(2026-10-12) 编排已下沉 `model/BookExport`（双壳接线，进度改 StateFlow 会话仍待做）；<br>~~B4/B5 书源批量操作双份拷贝~~ **勘误**(2026-10-12)：两壳已共用 BookSourceViewModel（数据层无拷贝），仅剩在线导入历史 ACache 胶水 ~20 行重复，降级低优；<br>B6 RuleSub 增删改直连 appDb 无 VM（`ui/rss/subscription/RuleSubActivity:157-260`）→ 迁移发现/订阅域前置；<br>B7 备份/恢复编排 ✅(2026-10-12 抽 help/storage/BackupRestoreController：startBackup(含进度写 BackupProgressHolder+全局协程)、S3满回退判定、ensureCloudStorageForRestore(返回类型由壳刷新)、listCloudBackups/listCloudAssetNames/restoreFromCloud、findLocalAssetsUris/restoreFromLocal、配置完备性判定；BackupConfigFragment 已改接控制器，MainViewModel.restoreWebDav 同走一条恢复链)。必须留在 UI：ActivityResult 契约、进度/确认弹窗（含宿主重建后重建）、权限申请、设置树渲染；<br>B8 新壳统计/阅读记录页直连 appDb ✅(2026-10-12 建 data/repository/ReadRecordRepository，HostStatsContent + HostReadRecordActivity 共用同一数据汇聚)；<br>B9 WebService/AutoTask 开关+态刷新在 Composable（HostMyContent）→ 抽 ServiceToggleController；<br>B10 源登录会话在 Dialog（`SourceLoginDialog` 35KB）；<br>B11 字体扫描/权限在 Dialog（`FontSelectDialog:93-208`）→ 抽 FontScanner；<br>B12 VideoPlayerActivity:886 UI 直接 startForegroundService；<br>B13 发现套件引擎抽取（即 #27-5，迁发现页前置）⏳(2026-10-13 第一步完成)：运行时状态类/常量/签名扩展（SuiteRandomDeck、SuitePreparedBatch、SuiteHorizontalPagingState、SuiteRankedPagingState、DiscoverySuitePageSnapshot(+Store)、DiscoveryCacheWriteScope、cacheSignature/deckSignature/分页签名/deckKey/suiteDeckKey）已从 ExploreFragment 的 file-private 搬成同包 internal 的 DiscoverSuiteRuntime.kt（零行为变更），并合并了 DiscoverySuiteHomeScreen 里重复的 deckKey。⏳(2026-10-13 第二步完成)：新建 DiscoverSuiteEngine（按屏实例，接收外部 scope + isActive 谓词），已下沉控件取书（普通/随机/横排/瀑布/排行）、随机牌堆填充、后台补牌与封面预取、预生成批次、横排/排行分页状态、唯一网络出口 loadTargetPage、按控件集合淘汰 retainWidgets、cancelAll/clearRuntimeState；ExploreFragment 对应方法改为委托，重复状态字段已删。⏳(2026-10-13 第三步完成)：快照缓存读写也下沉引擎（readSnapshotCache/saveSnapshotCacheAsync/snapshotCacheKey），共享的 readBounded/writeBoundedDiscoveryCache 下沉到 DiscoveryCachePolicy（现代发现与套件两域共用）。⏳(2026-10-13 第四步完成)：新建 DiscoverSuiteSession 单例会话（bind/stop + StateFlow 状态 + 全部编排：refreshConfig/selectSuite/首屏批量加载/单控件刷新/加载更多/快照存取，内含陈旧写回守卫），ExploreFragment 的套件编排全部改为委托会话、旧 compose 状态字段删除（渲染块直接 collect 会话流）。**套件归属裁决（2026-10-13，用户拍板）：套件聚合归主页域**。曾短暂接入 V5 发现页后撤除（发现 Tab 专注「选源→看该源发现分类」，与主页功能重合）。DiscoverSuiteEngine/Session/Runtime 全部保留，**主页 V5 化时直接接回**（bind + collect + 渲染即可）；DiscoverySuiteHomeScreen 的 fragment/lifecycle 参数已改可空（内部只透传从未调用）。ExploreFragment(158KB)+DiscoverySuiteManage(76KB)ngine）；<br>B14 阅读域巨类集群 ReadBookActivity(266KB)+20+大Dialog（分阶段：先朗读/配置后端化）；<br>B15 阅读记录统计+Widget 数据源住 ui 包 ✅(2026-10-12 ReadRecordWidgetStore 连同 ReadRecordRankItem/ReadRecentVisual*/ReadRecordGoalConfig 已迁 help/readrecord，旧页与新壳共用)；<br>B16 ThemeManageActivity(111KB) 主题包管理全量业务在 Activity<br>⚠️ 模式教训：**新壳迁移禁止复制旧 UI 逻辑，必须先抽引擎/控制器再写壳**（B2/B3、B4/B5 双份拷贝已在发生） | 逻辑写死界面，双份拷贝漂移风险 | 按域迁移期落地；B2/B5 双拷贝优先 |
| 29a | **新壳完全无法触达的功能**（旧入口随 MainActivity 删除断链）：BookCacheExportActivity（单书导出二级页）、RssFavoritesActivity（RSS 收藏，已由 6a 接线恢复）、DiscoverySuiteManageActivity、HttpTts 管理链（HttpTtsEditDialog/SpeakEngineDialog/ReadAloudConfigDialog，仅旧阅读页可达）、应用内 WebViewActivity（HostMyContent 只提供复制 URL/系统浏览器）；另有旧 SearchActivity/CacheActivity/AllBookmark 等已有 Host 等价页属死路正常 | 单书导出/HttpTTS 编辑/套件管理三个真缺口 | 随对应域迁移清 |
| 30 | **V5 组件缺口清单**（2026-10-12 审计，P0=马上要用）。P0：①HostV5EditPage ✅(2026-10-12 HostV5Page.kt，dirty+返回确认+保存动作) ②AppMultiFilterDialog ✅(2026-10-12，Set&lt;Int&gt; 回传) ③AppAsyncImage ✅(2026-10-12 app 侧 ui/host/AppAsyncImage.kt，移植 Legado_Max AppImage：Glide Drawable 链路+按尺寸降采样+动图 Painter) ④AppWebView ✅(2026-10-12 基础容器：state/进度/标题/URL/错误回调，JS 注入走 factory)。P1 已补：AppSlider 增强(steps/valueLabel/onValueChangeFinished)、AppNumberField、AppNumberPickerDialog、AppCollapsibleGroup、AppTaskRow、AppPullRefresh(M3 薄壳)、Modifier.appMinTouchTarget、AppFilled/GhostButton 补 enabled 态；2026-10-12 二批（参考 legado-with-MD3 分支）：AppFastScroller（移植 VerticalFastScroller/komikku，粘性头 key 用 APP_STICKY_HEADER_KEY_PREFIX）、AppSwipeActions(侧滑操作+无障碍+触觉)、AppVerticalSlider(旋转实现)、AppTimePickerDialog(HH:mm)、AppDropdownField 待做。P1 已补（2026-10-12 三批，MD3 移植收尾）：AppDropdownField（表单下拉字段，带标签/错误态）、AppColorField + AppColorPickerDialog（自绘 HSV 取色，ARGB Int 进出，未引三方色板库）。P1 剩余：AppPathField、AppCodeField、AppPhotoViewer、AppDetailSeekBar、AppListItem onLongClick（均为按需再补，当前页面无手搓对应物）。P2：日期选择（时间已补）、AppStickyHeader、内置目录浏览。<br>阅读界面为最大缺口：仅 ReaderBridge 契约，翻页体系(PageView/ContentTextView/8种PageDelegate/放大镜)必须 AndroidView 互操作。参考库：Legado_Max_compare 的 ui/widget/components/（设置行/对话框套件可后续白捡：AppSearchableChoiceDialog、AppAdaptiveDialog、SwipeActionContainer、FlexWrapLayout、CategoryTabs 等） | 管理列表/文件选择已够用；编辑表单/WebView/图片缺件已清 | 剩余 P1 随对应页面迁移补 |
| 31 | **已迁移页面复核结论**（2026-10-12）：方向正确不需重做（搜索/我的/书架/发现/书源管理架构对）；~~HostCacheActivity 导出编排去拷贝~~ ✅(2026-10-12 已接 model/BookExport)；书源页批量操作确认已共用 ViewModel 无需改；前置约束：发现页 V5 化前必须先抽 DiscoverSuiteEngine（B13） | 防止拷贝债固化 | 剩余项随各域迁移落地 |
| 32 | **手搓 UI → 组件替换**（2026-10-12 扫描约 20 处，已换 9 处）：AppInputDialog ×4（HostShelfSheets 分组命名/添加网址、HostHomeManageSheet 命名、HostRssSort 源内搜索，各删 ~40 行手搓弹窗）；AppSlider valueLabel（HostPackageEditors SliderRow 删文案拼装）；AppEmpty（HostHomeContent 暂无内容）；AppFastScroller 全量 ✅（2026-10-13 补齐 8 处：RSS源管理×2、替换规则、文件管理、TXT目录、字典、书架列表模式、RSS排序、书签；此前已有日志×2、书源、缓存管理，共 15 处长列表）。剩余：AppSwipeActions 属新增能力（文件管理/书签滑删）需产品确认后再接 | 统一观感、删重复代码 | 替换批次已完成 |
| 33 | **透明行在背景图上"隐身"**（2026-10-13 用户截图反馈，设置页开关/动作/滑条行无底色）：根因 = 四类行里只有 AppSelectionRow 自带 surface 72% 卡片底。修复 = 新增 `Modifier.appRowCard()`（AppModifiers.kt，单点定义行底色），AppListItem / AppRowWithSwitch 默认套上（card=true），弹窗内 3 处（AppDialogs.kt 菜单/单选/多选）与演示页 3 处（HostMuseumContent 在 AppCard 内）传 card=false 避免双层底，设置页滑条行（SettingSpecScreen 手写 Column）也套上。全项目 About/备份/日志/阅读记录等页的透明行一并修复 | 背景图主题下行不可辨 | 已完成，编译通过 |
| 34 | **迁移收尾批**（2026-10-13，全部编译通过）：<br>①**主页 V5 化收口**：发现套件接回主页（DiscoverSuiteSession bind/stop 挂 HostHomeContent，`HostHomeSuiteSection.kt` 以 LazyListScope 扩展复用 DiscoverySuiteWidgetSection——B13 裁决落地，DiscoverySuiteManageActivity 经主页"管理"入口恢复可达）；补下拉刷新（AppPullRefresh→viewModel.onRefresh）与模块内加载更多（BookRow 尾格 + 排行榜按钮，原 take(12) 截断取消）；<br>②**AI 悬浮球重接线**：MainAiFloatingBallController 接入 AppHostActivity（FrameLayout 根 + 覆盖层容器，从 9486fb8fe^ 旧 MainActivity 取回注入形态；可见探针简化为 AppConfig.aiAssistantEnabled）；<br>③**弹窗形状 V5 统一**：rememberAppDialogStyle 的 panelRadius/actionRadius 从 LayoutResolver.current 镜像 V5 形状包（颜色此前已 V5ThemeSync；胶囊→100dp 近似、斜切回落旧值），~50 个 AppDialogFrame 调用点单点生效；<br>④**死代码清理**：删 ExploreFragment/ExploreViewModel/ExploreModernListScreen、MyFragment、ReadRecordFragment、BookshelfFragment2+BaseBookshelfFragment、MainPageActivity+AppRoute、RssFragment/RssAdapter、HomepageFragment+View 弹层簇（ModuleManageSheet/ModuleEditDialog/KindSelectSheet）+adapter 簇（HomepageAdapter/BooksAdapter/GridRankingPager/RankingTab/BookBadge）+9 个布局、ThemeManageActivity（Manifest 孤儿）、MainFragmentInterface；Manifest 清孤儿注册 2 处；死事件 NOTIFY_MAIN/REVIEW_CACHE_SAVED/UP_CONFIG 常量+10 个发送点清除；3 个 menu xml tools:context 残留清理。**勘误**：activity_theme_manage.xml 非 ThemeManageActivity 专属，DiscoverySuiteManageActivity/TopBarManageActivity 复用之（已恢复保留）。保留：MainAiFloatingBall（已重接线）、DiscoverSuite 引擎四件套（主页在用）、MySettingsScreen（活） | 迁移整体完成，进入分 tab 验收期 | 用户逐 tab 核实功能缺失/bug 后分 tab 修 |
| 35a | **书架菜单漏注册崩溃**（2026-10-13 用户验收反馈）：书架集合（BookCollectionActivity）、智能分组（SmartGroupRuleActivity）自 2026-10-12 复活入口起就没进 Manifest（此前旧壳时代它们也不可达，属随旧壳退役的断链页面被菜单重新接出）；已补注册 singleTask。**全量排查**：脚本扫 ui/host 全部显式 Intent 跳转目标 vs Manifest，仅此 2 处漏网，其余全注册 | 点击闪退 | 已修，验证包 dev.26.101315 |
| 35b | **书架菜单多级页面仍为旧 View UI**（2026-10-13 用户反馈）：书架管理（BookshelfManageActivity）、标签管理、书架集合、智能分组、书库搜索、导入本地/远程书籍——均为旧体系页面，V5 化未做（属 P5「管理页逐个原生化」遗留，迁移整体完成≠所有二级页原生）。用户逐 tab 验收中体验割裂感强，**提级**：这些页是验收主要入口，应批量排期（书架管理/书架集合/智能分组优先，其次标签管理/书库搜索） | UI 观感割裂 | 待排期，随书架域 V5 化批量做 |
| 36 | **全量迁移收尾批**（2026-10-13，用户要求"做完全部迁移再验证"）：①新增 8 个 V5 页（全部编译通过）：HostBookshelfManageActivity（书架管理，VM 复用+选择模式+拖拽排序+换源进度+批量自动任务）、HostSmartGroupRuleActivity（智能分组，VM 复用，删除补确认）、HostLibrarySearchActivity（书库搜索，高亮⟦⟧搬运）、HostBookshelfTagManageActivity（标签管理，业务逐行搬运+两个弹窗 V5 重写）、HostImportBookActivity/HostRemoteBookActivity（本地/远程导入，BaseImportBookActivity 的 protected 方法抽成 ui/host/ImportBookShell.kt 共享）、HostBookCollectionActivity（书架集合，拖拽投放降级为选择条操作=功能等价，删除补确认）、HostExploreShowActivity（发现文章页，状态逐行搬运+ExploreShowComposeScreen 零拷贝复用+页码选择 Dialog）；②入口切换：书架菜单 7 项全切 V5；书源管理（HostExploreContent×2）、RSS 源管理（HostRssContent×3）、AI 工作区（AiConfigFragment→HostFileManageActivity）此前建了 V5 页没接线的全部补接；③Manifest 注册 8 个新页。**已知交互降级**：书架管理滑动框选→长按进入+全选/反选/选中区间；书架集合拖拽投放→选择条按钮；远程导入首次帮助弹窗→菜单项（迁移门规范）。**仍为旧 View 的入口（未迁，需下批）**：DiscoverySuiteManageActivity(77KB 套件管理)、~~AI 链~~ ✅(2026-10-13 五页全迁：HostAiProviderManageActivity / HostAiImageProviderManageActivity / HostAiWorldBookManageActivity / HostAiImageGalleryActivity，AiChatActivity 脚手架 BaseActivity→AppCompatActivity+setV5Content，删孤儿 activity_ai_chat.xml；均编译通过)、备份容器（S3/LibraryContainer 27KB）、~~AutoTaskActivity~~ ✅(2026-10-13 脚手架迁 AppCompatActivity+setV5Content，含 AutoTaskEditActivity，删孤儿布局)、RelaySettingsActivity(34KB)、~~RuleSubActivity~~ ✅(2026-10-13 脚手架迁 setV5Content，删 removeView hack+冗余 options menu+5 个孤儿布局/menu)、~~RssFavoritesActivity~~ ✅(2026-10-13 纯旧 View 重写：ViewPager+TabLayout+Fragment+Glide → HostV5Page+AppFilterChipRow 分组切换+LazyColumn+AppAsyncImage，点击读/长按删单条/溢出菜单删组删全部；删 3 旧文件+activity_rss_favorites.xml+rss_favorites.xml，fragment_rss_articles/item_rss_article 被 RssArticles 复用保留)、SharedReceiverActivity/CodeEditActivity/CoverHtmlActivity/WebViewActivity 等 association 长尾、RssSourceEditActivity/BookSourceEditActivity 编辑器 | 全 App 观感统一 | 书架域+发现域已完成；设置域 AI/备份/中转与 association 长尾待下批 |
| 35 | **书架验收批**（2026-10-13，用户逐 tab 检查第一批）：①分组卡加底色块（BookshelfGridItem 的 folder 分支套 appRowCard，padding 4→6，书籍卡不动）；②"返回书架分组"条 surface 0.82 实底 + primary 0.45 描边（原 primary 0.12 太透）；③书架菜单 MenuSection 分节色块（书籍=primary/管理=secondary/更新与缓存=warning/布局与工具=success，各 0.14 alpha + CARD 圆角）；④分组页 BackHandler（groupId≠root 时返回键回分组列表，HostScaffold 的 canBack 天然让位）；⑤**分组封面库**（新功能）：ThemePackageSpec 新增 groupCovers/groupCoversEnabled（缺字段向后兼容），主题包编辑器新增"分组封面库"节（GetMultipleContents 多选导入 kind=groupcover→filesDir/v5_assets/&lt;pkgId&gt;/、启用开关、清空），消费端 BookshelfComposeCover folder 分支按 groupId.hashCode() 稳定取库图（forcePath 直读本地） | 背景图主题下书架可辨性 + 分组封面个性化 | 已完成，编译通过；验证包 legado_app_H.dev.26.101314_29862316.apk |

---

**维护规则**：每完成一项 → 标注 ✅日期 并移到文末「已完成」区；新增遗留 → 登记到对应表。
本台账与 `docs/v5-migration-gate.md`（验收门）配套：门防止新遗留，账册跟踪旧遗留。
