# V5 退役与待办台账（Retirement Ledger）

> 目的：旧栈残留、迁移遗留、技术债的统一台账。每项有状态/影响/计划期。
> 规则：**每轮 P4/P5 收尾时清一批**；新增遗留必须登记，不允许「没人管」状态存在。
> 建立日期：2026-10-12（H.1.20.0 发布后）

---

## 一、功能级遗留（影响用户，优先清）

| # | 项 | 现状 | 影响 | 计划期 |
|---|---|---|---|---|
| 1 | **AI 悬浮球失效**（`MainAiFloatingBall`） | 旧壳删除后成孤儿代码，无入口驱动 | 用悬浮球快捷 AI 的用户功能没了 | P4-6：新壳重接线（或明确宣布废弃） |
| 2 | 管理页未迁移（书源✅已迁 / RSS源/替换/备份/缓存/文件/字典/自动任务/书签/关于/阅读记录/年度报告） | 旧 View + 主题桥（颜色跟随、chrome 旧） | 观感断层 | P4-3 按频率逐个（书源已完成，下一步 RSS 源） |
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
| 27 | 会话状态下沉（2026-10-12 全量扫描结论：旧 UI 独占消费 service/model 事件）。#1 书源校验 ✅ 已下沉 `model/CheckSourceSession`（服务写、UI collect，keepScreenOn 泄漏已修）；#2 Web服务/自动任务运行态 HostMyContent 只读一次不更新；#3 缓存/导出 4 事件待迁 CacheActivity 时接；#4 书架"单项更新中"状态新书架缺等价物；#5 ExploreFragment 私有 Suite* 预取会话（迁发现页前必须抽 DiscoverSuiteEngine）；死事件 NOTIFY_MAIN / REVIEW_CACHE_SAVED / UP_CONFIG 无消费者可清 | 按各功能迁移期落地 |
| 28 | **V5 框架 P0 缺口**（2026-10-12 组件审计，迁管理页/导入导出/RSS 的先决条件）：①导航返回栈+传参+BackHandler（HostState 只有平面切换，HostScaffold onBack 硬编码回书架）；②AppWebViewScreen（RSS 阅读/源登录显式跳旧体系）；③ManageListScaffold 页级选择模式脚手架（行级零件已齐，页级 500 行逻辑每页重写）。✅ 已补：AppOptionRow+AppTextField 扩展（多行/数字/错误态）、AppFormDialog/AppProgressDialog、Snackbar(带 action)+LocalHostOverlay 接线、AppFilePicker 门面 | 迁移前清 |

---

**维护规则**：每完成一项 → 标注 ✅日期 并移到文末「已完成」区；新增遗留 → 登记到对应表。
本台账与 `docs/v5-migration-gate.md`（验收门）配套：门防止新遗留，账册跟踪旧遗留。
