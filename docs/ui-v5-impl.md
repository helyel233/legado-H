# legado-H UI V5 实施手册（骨架先行 P0-P5）

- 状态：草案待确认（配合 `docs/ui-v5-skeleton-first.md` 使用；**视觉基准：`docs/demo/ui-v5-demo.html`**）
- 日期：2026-10-11
- 读者：实施者（含未来的 AI 实施会话）。每期自含：目标、前置、任务分解、文件锚点、验收清单、发版物
- 写法：任务编号带期号（P1-3 = P1 第 3 个任务）。文件锚点为 2026-10-11 代码现状，动手前先核对

> **本文档取代 `docs/ui-rewrite-plan-v4-impl.md`。** V4 的绞杀式顺序（往旧页面逐个接线、最后才拆旧的）不再执行。

---

## 第 0 章 总则（每期都要遵守）

### 0.1 冻结红线

| 红线 | 说明 |
|---|---|
| `ui/book/read/**` | 一行不改。每期收尾必含"读书界面逐项回归" |
| 视觉资产：AdvancedTip / AdvancedTitle（含 Lottie）/ reader-templates / 纸张纹理 | 冻结且**保全**：资源目录、解析代码、导入导出与云备份通道全不动；所有清理任务以此为黑名单 |
| 漫画/视频阅读 | `ui/book/manga/ReadMangaActivity.kt`、`ui/video/VideoPlayerActivity.kt` 本轮不动 |
| `modules/book`、`modules/rhino`、`app/data`、`app/service`、`app/web` | 业务与数据层不碰 |
| `docs/移植计划.md` | 私有文档，不在任何提交中引用 |

### 0.2 五条前提（用户裁定，不得打折）

1. **代码可以完全重写**——不要为了"少改"而保留坏结构。
2. **视觉现代化优先于贴近原版**——遇到"要不要保持原样"的犹豫，选更现代的。
3. **双包体系**：主题包管美术、界面包管非美术，都是多包库，判据是"换美术包要不要动几何"。
4. **读书页冻结 + 预留接口**（`ReaderBridge`），默认不调用。
5. **不做旧存档兼容**：不写迁移器、不双写、不留兼容分支。旧设置导入只在 P5 做一个一次性翻译器。

### 0.3 工程纪律

| 纪律 | 内容 |
|---|---|
| **唯一取值通道** | 任何视觉值只许走 `ThemeResolver` / `LayoutResolver`（P1 建）。新增一个直接读 SP 颜色键、或直接写 `Color(0xFF...)` / 硬编码 `.dp` 圆角的 kt 文件即违规 |
| **页面不许画外框** | Content 层拿不到顶栏/底栏/背景/圆角/动效的控制权。发现需要"这个页面特殊一点"时，先问是不是该加一个界面包全局参数，而不是给页面开后门 |
| **小步提交** | 每任务一个可编译提交，提交信息带任务号（如 `P1-3 navigation host four forms`） |
| **旧代码删之前先摘入口** | 确认一版无回归后物理删除；但本方案允许大删（见 6.2），不需要逐个过渡 |
| **功能保全询问制** | 搬迁中发现"旧页面有、新 Content 没接口承载"的功能，必须登记进方案 5.5(d) 式裁决表并**询问用户**，确认后方可动 |
| **免重建刷新** | 换主题包/界面包/日夜/密度，一律不 `recreate()`。旧的 `EventBus.RECREATE` 机制随 P1 退役 |

### 0.4 可复用底座（已存在，勿重建）

| 底座 | 位置（2026-10-11） | 规模 | 代号 |
|---|---|---|---|
| 种子色引擎 | `modules/theme/.../palette/SeedPalette.kt` | 136 行，38 角色 / 4 策略 / WCAG 自校，纯 Kotlin 有单测 | Palette |
| 主题包数据层 | `modules/theme/.../pack/ThemePackageSpec.kt` + `ThemePackageResolve.kt` | 130 + 45 行，容错解析 | ThemeSpec |
| 界面包数据层 | `modules/uikit/.../layout/LayoutPackageSpec.kt` | 88 行 | LayoutSpec |
| 键名真值表 | `modules/theme/.../apply/ThemeKeys.kt` | 152 行 | ThemeKeys |
| 安全解压 | `SafeZipExtractor` / `SafeZipLimits`（规范见 `docs/visual-resource-packages.md`） | — | SafeZip |
| 底部 drawable 工厂 | `app/.../ui/main/MainDrawables.kt` | 纯 Context，无 Activity 依赖 | Drawables |
| 侧栏手势状态机 | `app/.../ui/main/SideNavigationGestureDelegate.kt` | 62 行，无 View 引用 | SideGesture |
| 列表状态契约 | `modules/uikit/.../state/AppListState.kt` | 29 行，Idle/Loading/Content/Empty/Error | ListState |
| Toast 外观 | `modules/uikit/.../facade/AppToast.kt` | 94 行 | Toast |

**概念保留、代码重写**：`Applicator`（现 145 行）、`LayoutEngine`（现 109 行）——要扩维度（形状族/动效/纹理/材质分档）并去掉旧兼容分支，重写比改造干净。

**必须二选一**：`ThemePackageManager`（1712 行，格式 v2，云同步，RED 兼容）vs `ThemePackageStore` + `ThemePackageSpec`（102 行，seed 派生）。**决定：留新的**，老的连同 `ThemeConfig.Config` 一起删，云同步在后续期按新格式重做。

### 0.5 版本与发版

- **骨架验证期（P1-P3）不单独发正式版**：这三期产出的是"没有业务的漂亮空壳"，
  发出去用户会装到一个功能为空的 App。它们只以 debug 包形式交付、用于确认骨架，
  版本号沿用 H.1.19.0，不 tag、不 release。
- **从 P4 起每批发版**：业务逐页搬回来之后才有发布价值，新特性 minor +0.1
  （P4 第一批起 H.1.20.0）。versionCode = 29860000 + git rev-list count。
- 流程照 `docs/release-process.md`：CHANGELOG + updateLog 先提交再 tag；`build-signed.sh` 产物重命名为 `legado_app_H.<ver>_<code>_LegadoH_arm64.apk` 再跑 `create-release.sh`；release/debug 严禁并行，debug 前 `./gradlew --stop`。
- 每期收尾必跑：`./gradlew :app:assembleDebug` + 读书页冻结回归 + 本期验收清单。

### 0.6 现状速查（动手前核对）

| 项 | 现状 |
|---|---|
| 主界面 | `ui/main/MainActivity.kt` **2141 行**，兼宿主/导航/玻璃/侧栏/指示器/启动编排/剪贴板导入 |
| 主布局 | `res/layout/activity_main.xml`，48 个 View id，侧栏是手写伪 Drawer（非 DrawerLayout） |
| Tab（6 项，默认可见 3） | `MainBottomNavConfig.specs`：Homepage / Bookshelf / Explore / Rss / ReadRecord / My（Bookshelf、My 锁定可见） |
| Tab Fragment | `BookshelfFragment2`、`HomepageFragment`、`ExploreFragment`(158KB)、`RssFragment`、`ReadRecordFragment`、`MyFragment` |
| 页面数量 | `ui/` 下 104 个 `*Activity.kt`、38 个 `*Screen.kt`、`res/layout/` 231 个 XML，仅 24 个 Activity 挂了 `composeContent` |
| 设置宿主 | `ui/config/ConfigActivity.kt` 按 `configTag` 分发到 12 个 `ComposeSettingFragment`（`pref_config_*.xml` 现仅用于"我的"页搜索索引） |
| 外观 SP 键 | `constant/PreferKey.kt` 共 480 个 const，外观域约 70-90 个（L240-289、L439-461） |
| 换肤机制 | 终点是 `EventBus.RECREATE` → 各 Activity `recreate()`（P1 要废掉） |
| 组件库 | `modules/uikit` 仅 `AppButton`(124 行) + 状态视图，其余从零建 |

---

## P0 契约（纯设计 + 骨架接口）

**一句话**：把"骨架怎么和页面分工"写成代码里的接口，此时 App 外观不变。

**你看到什么**：这一期没有可见效果，App 和现在一样。产出是接口与规格。

### 任务分解

**P0-1 视觉语言规格定稿**
- 把方案第 3 章落成可执行规格：形状族 5 个（各自的圆角曲线/按钮/输入框/标签/图标容器定义）、材质四档 E0-E3 的参数、玻璃五件套、动效三档曲线与时长、字阶 6 级数值、密度三档系数、图标线宽与形变规则。
- 产出 `modules/uikit/.../token/` 下的形状族定义（纯常量，无 Android 依赖，可单测）。
- 与 `docs/demo/ui-v5-demo.html` 对齐——Demo 里能看到的，规格里必须有；规格里有的，Demo 里必须能演示。

**P0-2 Host/Content 契约**
- 定义 `AppContent` 接口（页面只实现这个）+ `ScreenChrome` 声明结构（标题/顶栏模式/操作项/滚动行为/材质需求/进入动画）。
- 定义 `AppDestination`（id / 图标 / 标题 / 内容工厂 / 可否为 Tab / 侧栏分组）。
- 定义 `ReaderBridge` 接口（方案 5.2）：可取语义色/字体/背景/纹理，三个接入档位（全跟随 / 部分 / 自治=默认）。**本轮不调用。**
- 关键约束：`AppContent` 的绘制作用域内**不提供**任何"自定义外框"的入口——这是机制保证，不是约定。

**P0-3 注册表契约与 6 Tab 迁移清单**
- 定义 `DestinationRegistry`（注册表 + 查询 + 排序 + 可见性）。
- 把现有 6 Tab 逐条登记为 `AppDestination`，并注明每个的来源 Fragment、现有功能入口、侧栏归属。
- 产出 `docs/ui-v5-destinations.md`：全量页面清单（104 个 Activity 分级：一级/二级/三级/冻结），作为 P4 搬迁的任务表。**这张表是 P4 的工单。**

**P0-4 取值解析器接口**
- 定义 `ThemeResolver`（颜色/字体/背景/纹理，响应式 Compose State）与 `LayoutResolver`（形状族/玻璃/密度/动效/图标/排版/栏材质，响应式）。
- 接口设计要点：返回 Compose `State`，改任何参数自动重组全 App，**不需要 `recreate()`**。
- 单元测试先行：给定种子色断言 38 角色对比度达标；给定界面包断言形状族/密度参数正确派生。

### 验收清单
- [ ] `AppContent` 接口中不存在任何"外框/圆角/背景/顶栏/底栏"的可覆写入口（代码审查确认）
- [ ] 5 个形状族、4 档材质、动效三档、密度三档均有常量定义且有单测
- [ ] 104 个页面分级清单完成，一级页面全部登记为 `AppDestination`
- [ ] `ReaderBridge` 接口已定义且默认档位 = 自治（读书页行为零变化）
- [ ] 本期不改任何现有界面代码（diff 只新增，不修改）

---

## P1 空壳 App（骨架本体）

**一句话**：一个只有骨架、没有业务的 App 能装能跑，四种导航形态能切，改形状/玻璃/密度/动效即时生效。

**你看到什么**：装上后打开，是一个很漂亮但功能全空的 App——6 个 Tab 只有占位内容，但底栏/侧栏/悬浮/顶栏四种形态能切换，形状、玻璃、密度、动效、主题色都能实时调。**这一步确认"骨架对不对"，确认完再填业务。**

**前置**：P0 完成。

### 任务分解

**P1-1 新 Host 骨架**
- 新建 `AppHostActivity`（取代 `MainActivity` 的宿主职责），只做五件事：渲染背景层 L0、渲染容器层 L1（顶栏/侧栏/底栏）、承载 Content 区、承载弹层宿主、处理系统栏与 WindowInsets。
- 分包拆职责（对照 MainActivity 现 2141 行的七个区块）：
  - `host/HostBackgroundLayer.kt`（背景 + 纹理 + 遮罩）
  - `host/HostTopBar.kt`（大标题折叠）
  - `host/HostBottomBar.kt`（标准底栏）
  - `host/HostFloatingDock.kt`（悬浮胶囊，复用 `MainDrawables.kt`）
  - `host/HostSideBar.kt`（侧栏，复用 `SideNavigationGestureDelegate`）
  - `host/HostOverlayLayer.kt`（弹窗/菜单/Toast/Snackbar 宿主）
  - `host/HostInsets.kt`（系统栏、刘海、手势区）
- 目标：`AppHostActivity` 本体 **≤300 行**，且其中不含任何具体绘制逻辑。
- 旧 `MainActivity` 本期内**并存不删**（新壳走独立入口，用 debug 开关切换），避免一次性大爆炸。

**P1-2 页面注册表**
- 实现 `DestinationRegistry`，登记 6 个占位 Tab。
- 宿主自动从注册表生成：底栏菜单、侧栏菜单、顶栏、内容路由、二级入口。**生成逻辑只有一份。**
- 旧 `MainBottomNavConfig` / `rebuildBottomNavigationMenu` / `realPositions` / `getFragmentId` 的"三处真相"问题，在这里一次性消灭。

**P1-3 四种导航形态**
- `nav.position` = bottom / float / side / top 四态，切换即时生效、不重建 Activity。
- 侧栏吃 `SideNavigationGestureDelegate`；悬浮胶囊吃 `MainDrawables`；顶栏形态作为降级（不需要侧栏手势）。
- 形态切换时**功能入口一个不少**——因为菜单来自注册表，不是各形态自己写。

**P1-4 取值解析器实现**
- `ThemeResolver`：接 `Palette`（SeedPalette）+ `ThemeSpec`，输出 38 角色 × 日夜 + 字体 + 背景 + 纹理，Compose `State`。
- `LayoutResolver`：接 `LayoutSpec` 扩展版（形状族/玻璃/密度/动效/图标/排版/栏材质），输出派生参数，Compose `State`。
- **删除 `EventBus.RECREATE` 在换肤路径上的使用**（P1 起换肤不重启）。旧 `ThemeConfig.applyTheme` 的 RECREATE 广播只在旧 `MainActivity` 路径保留，随 P4 退役。

**P1-5 占位内容 + 调试面板**
- 6 个占位 Tab 各显示一行字，用于验证导航与切换。
- 内置一个**调试面板**（仅 debug 构建）：实时切换形状族、玻璃五值、密度、动效、导航形态、种子色——供本阶段快速确认观感。**发布前移除或藏在开发者选项。**

**P1-6 收尾**
- `./gradlew :app:assembleAppDebug` 出 debug 包供骨架验证、**不正式发版**（见 0.5）。
- 读书页回归：本期未改任何业务页，应零差异。
- 新壳与旧壳并存，默认走**旧壳**（功能完整）；新壳入口：`我的 → 新骨架预览（V5）`，
  仅 debug 构建可见。

### 文件锚点
| 新建 | 说明 |
|---|---|
| `modules/uikit/.../host/` | 骨架契约与解析器 |
| `app/.../ui/host/` | `AppHostActivity` 及 7 个分包 |
| `app/.../ui/host/DestinationRegistry.kt` | 注册表实现 |

### 验收清单
- [ ] 空壳 App 能安装启动，6 个 Tab 能切，不崩
- [ ] 四种导航形态切换即时生效，功能入口完整，不重启
- [ ] 改形状族 / 玻璃 / 密度 / 动效，全 App 立即变，不重启
- [ ] `AppHostActivity` ≤300 行，本体无绘制逻辑
- [ ] grep 验收：`ui/host/` 下不得出现 `Color(0xFF` / 硬编码 `.dp` 圆角 / 直接读外观 SP 键
- [ ] 读书页逐项回归一致（本期未触碰）

---

## P2 组件库（现代化的零件）

**一句话**：把全套新组件建出来，用一个"组件博物馆"页面逐个确认观感。

**你看到什么**：空壳里多一个"组件博物馆"入口，里面摆着所有零件（卡片、列表、按钮、输入框、弹窗、骨架屏、空态、图标……），可以直接看到现代化长什么样。

**前置**：P1 完成（有骨架才能摆零件）。

### 任务分解

**P2-1 材质四档与基础容器**
- `Surface`（E0 平铺 / E1 嵌入 / E2 浮起 / E3 悬浮）统一实现：基底色 + 玻璃五件套 + 描边 + 阴影，全部读 `LayoutResolver`。
- `AppCard` / `AppPanel` / `AppGroup` / `AppCollapsible`：形状族驱动，支持 E1-E3。
- **关掉玻璃时**自动退化为实色 + 细描边（两套观感都要验收）。

**P2-2 列表与网格**
- `AppListItem`（单行/双行/三行，含前导/尾随/副标题）、`AppGridItem`、`AppGroupHeader`（吸顶）、`AppSwipeItem`（滑动操作）。
- 统一分隔线（弱化，跟随语义色）、按压反馈（涟漪 + 微缩放）。

**P2-3 导航组件**
- `AppTopBar`（大标题折叠，滚动时材质 E0→E2，标题字号压缩）。
- `AppBottomBar` / `AppFloatingDock` / `AppSideBar`（从 P1 的宿主分层下沉为可复用组件）。
- `AppTabRow` / `AppBreadcrumb` / `AppBackButton`。
- 图标选中态形变 + 微弹（M3 Expressive 风格）。

**P2-4 输入组件**
- `AppTextField` / `AppSearchField` / `AppSwitch` / `AppSlider` / `AppRadio` / `AppCheckbox` / `AppDropdown` / `AppStepper` / `AppColorPicker`。
- 全部走形状族 + 语义色，按压/聚焦/错误态都有动效。

**P2-5 反馈组件**
- `AppDialog` / `AppBottomSheet` / `AppMenu` / `AppSnackbar`（`AppToast` 沿用并翻新外观）/ `AppProgress`（线性/环形/不确定）/ `AppConfirm`。
- **骨架屏** `AppSkeleton`（shimmer，形状跟随内容布局）。
- **空态** `AppEmpty`（插画 + 人话 + 行动按钮）、**错误态** `AppError`（可重试）。

**P2-6 展示组件**
- `AppCover`（封面：占位底 + 书名首字 + 骨架 + 失败占位 + 按显示尺寸采样解码）。
- `AppAvatar` / `AppChip` / `AppBadge` / `AppCornerMark`（更新角标）/ `AppDivider` / `AppRating`。
- 图标体系：统一线宽（界面包参数）+ 描边/填充切换 + 选中形变。

**P2-7 组件博物馆页 + 收尾**
- 一个页面把所有组件按类别摆出来，支持在页内切换形状族/玻璃/密度/动效/主题色实时预览。
- 发版 H.1.21.0。

### 验收清单
- [ ] 所有组件读 `ThemeResolver` / `LayoutResolver`，grep 无硬编码颜色/圆角/间距
- [ ] 玻璃开/关两套观感都通过（关掉后是干净实色卡片，不是一片糊）
- [ ] 骨架屏、空态（含插画和行动按钮）、错误态（可重试）在博物馆页可看可验
- [ ] 图标线宽全 App 一致、选中形变生效
- [ ] 博物馆页内切换任意参数，所有组件同步变化
- [ ] 读书页逐项回归一致

---

## P3 包体系（双包库 + 管理中心）

**一句话**：能建包、换包、包内编辑、另存自定义包；切主题包/界面包全 App 秒变不重启。

**你看到什么**：空壳里"我的 → UI 设置 → 主题和界面管理中心"能用起来，官方包 + 自己建的包都能选、能改、能删。

**前置**：P2 完成（有组件才能做包内编辑器 UI）。

### 任务分解

**P3-1 包格式 v2 定稿**
- `ThemeSpec` 扩到 v2：新增 `texture`（纸张/噪点纹理 + 不透明度）、`images` 三组 × 日夜（现状已支持）、保留 `override` 逃逸口。
- `LayoutSpec` 扩到 v2：新增 `shape.family`（5 族）、`motion`（三档 + reduce）、`icon`（style/stroke/selectedMorph）、`type`（titleWeight/cjkSpacing）、`bar`（topElevation/bottomElevation/collapse）；`glass` 扩为五件套（alpha/blur/stroke/noise/vibrancy）。
- 容错解析保持：缺字段回默认、未知字段忽略、半成品包能加载。

**P3-2 内置包**
- 主题包 6 个：晴空白（默认）、墨夜黑、AMOLED 纯黑、纸张米、动态取色（壁纸主色，Android 12+）、电子墨水。纯资产，无 kt。
- 界面包 5 个：经典（soft/无玻璃/标准动效）、**悬浮玻璃（round × 1.2 / 强玻璃 / 生动动效，设为默认）**、侧栏沉浸（side）、极简（sharp/紧凑/克制）、花瓣（petal × 1.3）。
- **默认界面包改为"悬浮玻璃"**——本轮视觉现代化的第一眼。

**P3-3 包库与持久化**
- `ThemePackageStore` / `LayoutPackageStore` 从"单 active + 单 custom"升级为**用户包列表**（新建/重命名/克隆/删除/导入/导出）。
- 存储：每个用户包独立记录（id/name/参数/资产引用），`basedOn` 记录基包。
- 本方案**不做旧存档迁移**（前提 5）：旧 `ThemePackageManager`(1712 行) / `ThemeConfig.Config` / `AppearanceKitManager` / `MainLayoutPresetConfig` / `NavigationBarIconConfig` 在此期**物理删除**；`ThemeKeys`(152 行) 保留给 P5 导入器用。
- 云同步：本期内**不接**（旧的随 ThemePackageManager 一起删），需要时另开一期按新格式重做。

**P3-4 包内编辑器**
- 主题包详情：种子色取色器 + 实时预览、夜间策略（tonal/amoled/muted/mono）、38 色逐项覆盖、字体选择、三组背景图（日夜）、纹理。
- 界面包详情：导航形态、形状族 + 缩放、玻璃五值（带实时预览条）、密度、动效强度、图标风格与线宽、标题字重与中文字距、顶栏/底栏材质与折叠。
- **另存模型**：官方包受保护，改动即克隆为"我的包"；自定义包显示"已修改项"（相对 basedOn 的差异），支持单项还原和整包删除回基包。

**P3-5 管理中心 UI**
- 入口：我的 → UI 设置 → 主题和界面管理中心（沿用 V4.10 入口链，本次用新组件重写）。
- 两个子区（主题选包 / 界面选包），每区 = 官方包 + 用户包列表，点包即应用。
- **应用不重启**：走 P1 的响应式解析器。

**P3-6 收尾**
- 发版 H.1.22.0。

### 验收清单
- [ ] 切主题包 3 秒内全局变色变字体，不重启；深色模式自动协调、正文对比度达标
- [ ] 切界面包四种导航形态正常、形状族/玻璃/密度/动效同步变化，不重启
- [ ] 建 ≥2 个主题包 + ≥2 个界面包，切换/编辑/克隆/重命名/删除互不影响
- [ ] 编辑官方包自动克隆为"我的包"，差异项可单项还原
- [ ] 半成品包（删一半字段）导入正常，缺项回默认；恶意路径/超大 zip 被拒且提示人话
- [ ] 旧包管理双轨已合并为一轨（grep `ThemePackageManager` 应为 0 命中）
- [ ] 读书页逐项回归一致

---

## P4 搬迁（业务一页一页搬进骨架）

**一句话**：按使用频率把业务页搬进新骨架，没搬的自动套新外框。

**你看到什么**：App 开始"有内容"了——先是 6 个 Tab 回来，然后详情页、搜索、设置……每一批回来一批功能，观感全程是新的。

**前置**：P3 完成（骨架、组件、包体系都齐了才开始搬）。

### 任务分解

**P4-0 降级容器 `LegacyScreenHost`**
- 给尚未搬迁的旧 Activity 自动套新外框：新顶栏（含返回/标题/操作）、新背景与纹理、新材质弹层、新动效。旧页面内部 XML 原样运行。
- 作用：**第一天就统一观感**，不必等搬完。
- 验收：随便打开一个未搬迁的管理页，外框与已搬迁页完全一致。

**P4-1 第一批：6 个 Tab**
- `BookshelfFragment2` / `HomepageFragment` / `ExploreFragment` / `RssFragment` / `ReadRecordFragment` / `MyFragment` 逐个改写为 `AppContent`。
- 书架额外落地方案 3.5 的现代化：5 种布局档位（封面墙/三列/四列/双列卡片/极简列表）、封面 2dp 进度条、更新角标、继续阅读卡、骨架屏加载、按显示尺寸采样解码。
- **书架专属的只有布局选择**；圆角/间距/玻璃/颜色/字体全部来自全局（grep 验收：书架文件不得新增视觉常量）。
- 每搬完一个，旧的 Fragment 摘入口 → 验证一版 → 删除。

**P4-2 第二批：书籍详情 + 搜索 + 阅读记录 + 订阅**
- 详情页：大标题折叠顶栏、封面共享元素、容器变换入场。
- 搜索：搜索框走新组件、结果骨架屏、历史记录芯片。
- 双胞胎页面在此批收口：`BookInfo` 两套取 Compose 版，`Explore` 两套取一套，书架三套取一（删除前置：确认 Compose 版功能齐全，含横屏分页/富文本 intro）。

**P4-3 第三批：书源管理 + 备份恢复 + 书源编辑 + 替换净化**
- 这些页面较重（`ExploreFragment` 158KB、`BookSourceEditActivity` 36KB 纯 XML），允许分批：先套 `LegacyScreenHost` 保证观感，再逐个升级为真 Content。
- 每个页面升级前先登记功能清单，升级后逐项对照（功能保全询问制）。

**P4-4 第四批：其余管理页**
- 按优先级升级；本轮结束仍未升级的，继续走 `LegacyScreenHost`（不影响观感统一）。

**P4-5 旧壳退役**
- `MainActivity`(2141 行) / `activity_main.xml`(48 id) / 旧侧栏全栈 / 旧玻璃调度 / 旧指示器 / 旧启动编排 —— 物理删除，启动入口切到 `AppHostActivity`。
- `EventBus.RECREATE` 换肤路径彻底移除。
- `res/xml/pref_config_*.xml` 12 个删除（P5 新设置上线后）。
- 发版 H.1.23.0。

### 文件锚点（搬迁对照）
| 旧 | 新 |
|---|---|
| `ui/main/MainActivity.kt` | `ui/host/AppHostActivity.kt` + 7 个 host 分包 |
| `ui/main/bookshelf/style2/BookshelfFragment2.kt` | `ui/content/bookshelf/BookshelfContent.kt` |
| `ui/main/homepage/HomepageFragment.kt` | `ui/content/homepage/HomepageContent.kt` |
| `ui/main/explore/ExploreFragment.kt`(158KB) | `ui/content/explore/ExploreContent.kt` |
| `ui/main/my/MyFragment.kt` | `ui/content/my/MyContent.kt` |
| `ui/config/ConfigActivity.kt` + 12 Fragment | P5 的新设置 |

### 验收清单
- [ ] 6 个 Tab 全部搬进新骨架，功能齐全（逐项点通）
- [ ] 未搬迁的旧页自动套新外框，观感与主页面一致（用户看不出两个体系）
- [ ] 换任意主题包/界面包，已搬迁页与未搬迁页同步变装
- [ ] 书架 5 种布局即切即见，进度条/角标/继续阅读卡正常；书架文件无本地视觉常量
- [ ] 双胞胎页面删净，导航全通（107 个 entry 逐一点通）
- [ ] `MainActivity` / `activity_main.xml` / `EventBus.RECREATE` 换肤路径清零
- [ ] 读书页逐项回归一致

---

## P5 设置与收尾

**一句话**：全新设置界面；旧版本设置可一次性导入；皮肤制作教程。

**你看到什么**：设置焕然一新（可搜索、左调右看、实时预览）；老用户可以从旧版本导入设置。

**前置**：P4 完成。

### 任务分解

**P5-1 新设置界面**
- 信息架构按方案 5.2：UI 设置（管理中心 + 界面杂项）/ 阅读（冻结搬运）/ 朗读（冻结搬运）/ 书源与内容 / 行为与提示 / 网络与性能 / 媒体与音频 / AI / 备份与恢复 / 隐私与安全 / 杂项 / 关于。
- 全 Compose + 声明式设置项（沿用已验证的 `SettingSpec` 形态），分区折叠 + 常用置顶 + **设置搜索**。
- 外观区"左调右看"：左列设置项，右侧实时预览沙盒（复用 P2 真实组件：迷你书架 + 迷你详情 + 迷你弹窗三块样机）。
- `res/xml/pref_config_*.xml` 12 个在此期删除（搜索索引改由设置项声明提供）。

**P5-2 旧版本设置导入器（一次性翻译，不是兼容）**
- 入口：我的 → 设置 → 从旧版本导入。
- 翻译规则（数据源 `ThemeKeys`，保留它的唯一用途）：
  - 旧主题色 → 新主题包种子色
  - 旧背景图（`bgImage` / `bookInfoBgImage` / `panelBgImage`）→ 新主题包 `images`
  - 旧 `uiCornerScale` / `uiLayoutAlpha` / `dialogAlpha` → 新界面包 `shape.scale` / `glass.alpha`
  - 旧 `bottomBarLayoutMode` / `mainLayoutPreset` → 新界面包 `nav.position`
  - 旧功能设置（非外观）→ 新设置项，按键名映射表
- 产出"我的主题包 1" + "我的界面包 1"，**可预览、可放弃**（预览不落盘，确认才写）。
- 明确：不导入 = 全新开始。不做后台自动迁移。

**P5-3 组合矩阵验证**
- 6 主题 × 5 界面包 × 极端参数（玻璃 0/1、形状缩放 0/1.5、动效三档、最大字号、精简导航开/关）自动遍历，主线页面截图比对，异常组合回默认不崩。

**P5-4 皮肤制作教程 + 收尾**
- `docs/skin-authoring.md`：theme.json / layout.json 字段白话解释 + 最小示例包 + 常见错误对照表；示例包入库 `docs/samples/`。
- CHANGELOG 汇总 V5 全景，发版 H.1.24.0。
- `docs/ui-v5-skeleton-first.md` 状态改为"已实施"，读书界面 B 系列另立文档。

### 验收清单
- [ ] 设置搜索"透明度"/"形状"/"字体"可直达；外观区拖滑块预览即时变
- [ ] 阅读/朗读分区旧项逐项可用（冻结验证）
- [ ] 旧版本设置导入：主题色/背景图/功能设置基本都在，可预览可放弃；不导入时全新开始无异常
- [ ] `res/xml/pref_config_*.xml` 清零
- [ ] 矩阵全组合无崩溃、无对比度违例
- [ ] 教程 + 示例包可被零基础用户照做成功
- [ ] 读书页逐项回归一致（最后一次冻结验证）

---

## 附：依赖关系与并行机会

```text
P0 契约 ──→ P1 空壳 ──→ P2 组件库 ──→ P3 包体系 ──→ P4 搬迁 ──→ P5 设置
   │           │
   │           └── P1-5 调试面板可与 P2 组件并行开发（互不依赖）
   └── P0-3 页面分级清单可在 P1 期间并行（纯文档工作）

P4-0 降级容器（LegacyScreenHost）在 P4 最前面做，可与 P4-1 并行
P5-2 导入器只依赖 ThemeKeys，可提前起草
```

**风险缓冲**
- 每期预留 20% 时间给冻结回归返工。
- P4 是最重一期（104 个页面），若超期：只保证第一批（6 Tab）完整搬迁，其余全部走 `LegacyScreenHost` 交付——**观感统一不受影响，只是旧页面内部仍是老控件**。这是可接受的降级。
- P1 是"骨架对不对"的唯一确认点，用户确认前不进 P2。

