# 全 App 玻璃材质可接入面勘查与分阶段接入方案

> 勘查日期：2026-11-09。基准观感：主界面底栏（`StableLiquidGlassView` + shell overlay）。
> 本文件是接入工作的唯一方案来源；每阶段完成后的偏差应回写到这里，而不是散落在提交说明里。

## 0. 结论摘要

- 玻璃系统（材质参数、四档降级、液态 AGSL）已经稳定，**不需要新造任何一套数学**；后续所有接入只做两件事：选对入口、选对采样域。
- 决定「能不能接、怎么接」的不是页面名，而是**宿主视觉后方是什么**：
  - 后方是壁纸层 → 现有 `ExploreGlassBackdrop` 壁纸采样管线直接可用；
  - 后方是不透明卡片/面板/封面/书页/视频 → 壁纸采样会折射出「实际不存在的背景」，必须先解决采样源（兄弟子树采样）或只走实底壳。
- 按此判据重新核对后：发现页弹窗控件、套件管理预览控件、键盘工具条均**不属于**壁纸域（它们在面板内），从原 P0 名单移出；P0 收敛为选择模式底栏 `SelectActionBar`。

## 1. 现有玻璃系统（接入面）

| 入口 | 形态 | 适用宿主 | 备注 |
|---|---|---|---|
| `StableLiquidGlassView` | View，固定位置浮层 | 底栏、顶栏 `MainTopBarView` | 采样源必须是**兄弟子树**；**禁止放入 RecyclerView 行内**（1.25.9 native 崩溃） |
| `io.legado.app.ui.glass.GlassSurface`（跨包入口）/ `ExploreGlassBackdrop`（发现页内部） | Drawable，任意 View | 行、控件、底栏、面板 | 同一份 `glassDrawable`，四档降级；入口要求**显式采样域**，非壁纸域返回 null；采样源 = 壁纸位图按屏幕坐标对齐 |
| `ExploreGlassPreview` | 设置页预览 | 设置页 | 与生产同一入口 |

材质唯一来源：`GlassMaterialPreset.current(mode)`。四档降级：①液态 AGSL（API 33+，`RowGlassLiquidRenderer`）→ ②硬件高斯（API 31+）→ ③软件预模糊位图 → ④实底壳（无玻璃档 / 动图壁纸纯色底 / 设备降级）。

**档位差异（1.25.14 起）**：透明玻璃与磨砂玻璃不再只在着色上分深浅，而是同时拉开四条可见维度——模糊（透明 3–6dp / 磨砂 20–32dp）、白色着色（0.004–0.014 / 0.18–0.28）、色散（0.24–0.36 / 0.04–0.08）、折射几何（高 24–36dp、偏 46–72dp / 高 12–15dp、偏 20–32dp）。旧取值下两档切换在真机上差异不足；壳层 / 描边 / 实底公式保持 1.25.5 口径不变。所有宿主（底栏、顶栏、发现页行、各玻璃面板）自动同享这一份差异，不新增偏好键。

## 2. 决定性约束：采样域判定

### 2.1 壁纸域（可用现有壁纸采样管线）

判据（三者同时成立）：

1. 宿主所在 Activity 的背景图开关为默认开启：`BaseActivity(imageBg = true)`，`upBackgroundImage()` 会把主题背景图设置到 `window.decorView`；
2. 设备上存在可用背景图（`ThemeConfig.hasUsableBgImage`），此时 `applyRootBackgroundPolicy()` 会把根布局背景置空、透出 decorView 背景图；
3. 宿主到根布局的路径上没有不透明容器（卡片、面板、封面、书页、视频画面）遮挡壁纸。

满足时，`ExploreGlassBackdrop` 解码的壁纸位图（`MainThemeBackgroundState`，同源同裁剪）与屏幕坐标对齐，折射/模糊内容即用户实际看到的背景。
无背景图时管线回退为纯色底图，玻璃表现为「实色磨砂」，仍然自洽；动图壁纸默认走实底壳（避免「壁纸在动、玻璃静止」）。

### 2.2 面板域 / 内容域（壁纸采样不可直接使用）

宿主视觉后方是**不透明面板或非壁纸内容**：对话框卡片、Compose 面板（可带面板背景图）、封面、书页、视频帧。此时壁纸采样会画出「被面板遮住、用户根本看不到的壁纸」，观感与周围不连续，属于错误管线。

处理路径二选一：

- 面板本身玻璃化（整个面板一个玻璃层，面板内控件跟随面板材质，不单独采样）；
- 用 `StableLiquidGlassView` + 兄弟子树采样源（顶栏 1.25.10 已验证的模式），采样真实内容。

### 2.3 勘查修正记录

初版勘查把以下三处误列为壁纸域，经代码核对后归入面板域：

| 表面 | 实际结构 | 结论 |
|---|---|---|
| 发现页书源类型弹窗内控件（`ExploreFragment` 5 处） | `UiCorner.opaqueRounded(themeCardColor)` 不透明对话框面板 | 面板域，移出 P0 |
| 套件管理预览控件（`DiscoverySuiteManageActivity` 6 处） | Compose `ClassicDiscoverPreview` 面板（带 `panelImage` 背景） | 面板域，移出 P0 |
| 键盘工具条（`KeyboardToolPop` 4 处） | 弹层内 RecyclerView 显式设置不透明卡片底色 | 面板域，移出 P0 |

## 3. 候选面盘点

| 类 | 表面 | 域 | 接入方式 | 阶段 |
|---|---|---|---|---|
| A | **选择模式底栏 `SelectActionBar`**（书源/字典/RSS 源/替换规则/导入书/远程书/书架整理/书架管理/TXT 目录规则，7 个布局、8+ 宿主页面） | 壁纸域（`imageBg=true`，底栏照旧遮在列表上，与主底栏同语义） | `panelBackground(host)` drawable | **P0** |
| A | 发现页行 / 行内控件 / 顶部浮层 | 壁纸域 | 已完成 | 已上线 |
| B | 「我的」页卡片与按钮（Compose，壁纸正上方） | 壁纸域 | `GlassPanel`（Compose 桥） | **P2 已实施** |
| B | 搜索页（Compose，`imageBg=true`） | 壁纸域 | `GlassPanel`（输入框） | **P2 已实施** |
| C | 阅读页底部菜单与顶部菜单（Compose，浮在书页上） | 内容域 | `StableLiquidGlassView` + 兄弟子树采样（`read_view`） | **P3 / P4 已实施** |
| C | 音频播放控制面板、视频控制条 | 内容域 | 无合法兄弟子树采样源（背景由自身 Compose / 播放器 Surface 绘制）→ 需先把氛围层重构为真实 View 兄弟节点 | P4 维持实底 |
| C | 书籍详情封面上的信息卡 | 内容域（`imageBg=false`） | 底色 87% 不透明 + scrim 叠加补偿；换玻璃会直接降低正文可读性 | P4 维持实底 |
| C | 发现页弹窗、套件管理预览、键盘工具条、书源/RSS 调试页 | 面板域 | 发现页弹窗经 `ModernActionPopup` 统一宿主已随同接入；套件预览无内容采样源、键盘工具条是独立 `PopupWindow` 窗口，均维持实底 | P4（弹窗已实施，其余维持实底） |
| D | `ModernActionPopup`（发现页弹窗、行菜单、`showFromMenu` 统一宿主） | 面板域（无 scrim） | 面板整体玻璃化 + 行底色让位 | **P4 已实施** |
| D | 通用对话框、`SourceLoginDialog` | 面板域 + scrim 叠加 | 跨窗口绘制，先用 scrim/玻璃叠加补偿方案评估 | P4 维持实底 |
| — | 主界面侧边导航面板 | 面板域（可带自有背景图） | 整体玻璃化 + scrim 补偿 | **P4 已实施** |

## 4. 分阶段方案

### P0 · 壁纸域底栏收口（本次实施）

- `ExploreGlassBackdrop` 新增公共入口 `panelBackground(host)`（圆角 `ui_panel_radius`，与主底栏 shell overlay 圆角逐值一致），`rowBackground` 委托给它，不改变现有行为。
- `SelectActionBar` 背景由 `bottomBackground` 实色替换为 `panelBackground(this)`；返回 null（墨水屏 / 低内存 / 动图壁纸纯色底）时回退原逻辑（`transparentNavBar` 保持透明，否则实色 + elevation）。
- 不做：面板域控件（见 2.3）、TitleBar 全局改造（共享控件，待 P1 收口后单独评估）。

### P1 · 架构收口（已实施，不改观感）

**实现形态**：新增 `io.legado.app.ui.glass` 作为跨包唯一入口，内部仍委托探索页那条已验证的管线。本次**不做** 1.5k 行文件的物理搬迁：`ExploreGlassBackdrop` 里大量符号是发现页专属（偏好键、迁移、预览、配置签名），搬迁只会把「入口命名」问题换成「包划分是否合理」的新问题，且会在这份未提交工作区里制造大范围重命名。目标是让**调用方**不再 import 探索页包，实现位置随 P2/P3 渐进内聚。

- `GlassSamplingDomain`（`WALLPAPER` / `PANEL` / `CONTENT`）+ 纯函数 `glassBackdropSource` / `glassSurfaceDrawableAllowed`：壁纸域 → `WALLPAPER_BITMAP`；面板域 → `OPAQUE_SHELL`（入口返回 null，宿主保留原有不透明背景）；内容域 → `SIBLING_SUBTREE`（入口不提供，调用方必须用 `StableLiquidGlassView`）。JVM 单测锁死整张表（`GlassSamplingDomainTest`）。
- `GlassSurface`：`panelBackground / rowBackground / controlBackground` 均要求显式传采样域（**无默认值**），非壁纸域一律返回 null；`filletControlBackground(host, domain)` 是「玻璃 ?: 主题纯色控件背景」的唯一实现。
- 已迁移调用方：`SelectActionBar`（壁纸域底栏）、`ExploreAdapter`（行标题 / 行内控件）。面板域既有调用点（发现页弹窗、套件预览、键盘工具条、登录弹窗）继续直接用主题纯色控件背景，待 P4 面板整体玻璃化；其中发现页弹窗随 P4 `ModernActionPopup` 统一宿主一并接入。
- 为什么不做运行期祖先透明度探测：任意外层容器是否不透明取决于布局、主题与运行时状态，无法用纯函数可靠判定；把判定写成猜测会产生假阴性（该拒绝却放行）。显式声明采样域 + 纯函数表 + 单测是唯一可确定性的防线。

### P2 · Compose 桥（已实施）

**实现形态**：`io.legado.app.ui.glass.GlassPanel` 用一个 `FrameLayout` 宿主经 `AndroidView` 承载同一份 `GlassSurface.panelBackground`，Compose 内容作为同层后续节点叠在其上。宿主只声明采样域与圆角，材质 / 四档降级 / 描边 / 采样坐标全部沿用底层管线，**没有第二套参数**。

- **可用性在组合期判定**：`glassSurfaceAvailable(domain, mode, isEInk, isLowRam)` 是 `glassDrawable()` 早退分支的纯函数镜像（无玻璃档在设备降级之前判定）；判不可用直接铺 `fallback` Modifier，不创建空 View。JVM 单测锁表（`GlassSurfaceAvailabilityTest`）。
- **重绑键 = 现有配置签名**：`rememberGlassConfigSignature()` 每个页面注册一个偏好监听，把 `ExploreGlassBackdrop.configSignature()` 提为 Compose State；签名进 `GlassPanelHost.bind` 的去重键（`签名|域|圆角`），设置变化等价于发现页行重绑。不新增偏好键、不新增监听集合。
- **位置跟踪复用 TrackerBinding**：`glassDrawable` 挂 drawable 时已绑定宿主的 attach / layout / `OnPreDraw`，Compose 列表的平移（不触发子 View 布局回调）由 `OnPreDraw` 覆盖，因此桥内**不再**自写逐帧坐标检测（自写版本晚一帧生效）。
- **叠放顺序已核实**：interop `AndroidViewHolder` 的绘制由 Compose 绘制流程按布局节点顺序调用（`AndroidViewHolder` 的 draw block → `AndroidComposeView.drawAndroidView` → `AndroidViewsHandler.drawView`；`AndroidViewsHandler.dispatchDraw` 为空实现，不会在合成内容之上二次绘制）。因此 `AndroidView` 声明在内容之前即「玻璃在内容下方」。
- **页面透明前提（勘查修正）**：「我的」页与全库搜索页原先在 Compose 根上铺不透明页色，把壁纸整页盖住——此时接玻璃采样的是**看不见的壁纸**，正是 2.3 的错误管线。两页改为在「背景图可用且非墨水屏」时页面背景透明（纯函数 `glassPageBackgroundColor`，与 `BaseActivity/BaseFragment.applyRootBackgroundPolicy` 同一判定），无背景图 / 墨水屏时保持原页色。
- **落点**：「我的」页分区卡片与搜索空态框（`SettingsSectionCard` / `EmptySettingsFrame`）；全库搜索页（`LibrarySearchActivity`）输入框。搜索结果行属高数量列表行，按第 5 节性能边界**不**逐行玻璃化。
- **已知偏差**：接入玻璃后，正常设备上「我的」页卡片不再显示用户自定义面板背景图（`panelImage`），无玻璃档下卡片外观由主题卡色变为底栏同源的实底壳——与底栏 / 发现页行的「无玻璃 = 不透明底栏色」口径一致。

### P3 · 内容域折射（复制顶栏先例）

**实现形态**：阅读页底部菜单（`ReadMenu` 的 Compose `Surface`）改为「真实玻璃层 + 透明面板」，玻璃层是 `ReadMenu` 的**子 View、在 Compose 内容之前**，采样源为阅读页正文 `read_view`（与 `read_menu` 平级的兄弟子树，与顶栏 1.25.10 同一守卫 `selfOrAncestorContains`）。

- **单一参数表**：新增 `io.legado.app.ui.glass.LiquidGlassSpec` + `liquidGlassSpec(params, density)`，顶栏原来的 `TopBarGlassSpec/topBarGlassSpec` 删除并改为同一函数；材质、倍率与四档降级仍只来自 `GlassMaterialPreset.current(mode)`，没有第二份公式。
- **启用判定纯函数**：`contentLiquidGlassSpec(params, density, sdkInt, sourceAvailable, isLowRam, isEInk)`，任一否决返回 null = 宿主保持原有实底表面。否决条件分别是「无合法采样源」「API<33 无 RuntimeShader」「低内存 / 墨水屏每帧整树录制不可接受」；solid 档由 `liquidGlassSpec` 自身返回 null。JVM 单测锁表（`LiquidGlassSpecTest`）。
- **绑定时机 = 菜单可见期**：底层库把 `OnPreDrawListener` 挂在**采样源**的 ViewTreeObserver 上，常驻绑定会让人在阅读时每帧把整页录制进 `RenderNode`。因此 `runMenuIn()` 绑定、`runMenuOut()` 动画结束（300ms 后清理块）解绑；菜单收起时不采样。
- **几何**：面板矩形来自 Compose 已有的 `onGloballyPositioned`（同一次回调顺带更新 `bottomMenuBounds`），玻璃层逐帧跟随，滑入/滑出动画不需要另写一套动画。底边额外向下延伸一个圆角（纯函数 `glassPanelRect`），把下方两个圆角推到父容器裁剪区之外——玻璃层只有单一半径，否则底部两角会挖出缺口。
- **EPUB 直读不接**：直读模式的正文是 WebView（`EpubDirectWebLayer`），`RenderNode` 录制 WebView 不可靠；且该模式下 `read_view` 本身已 `GONE`（`isShown=false` 是无源否决）。双重门禁，保持实底菜单。
- **宿主测量（真机修复）**：玻璃层以 `LayoutParams(0, 0)` 加入、位置由面板矩形决定，父容器因此测不到有效尺寸，内部玻璃子 View 停在 0×0、整层空绘制——真机表现是玻璃 / 磨砂档下阅读页底部菜单背景整体消失（页面文字原样透出、无模糊无着色）。修复两条：①定位时把 `LayoutParams` 同步为目标尺寸并按 `EXACTLY` 测量一次（尺寸只随面板变化，滑入滑出期间只改位置，不触发重新测量）；②面板只在「玻璃已激活且已完成一次定位」时让出底色（纯函数 `glassPanelFillYielded` + 单测），未定位时保持实底——即使玻璃链路在个别设备上失败，菜单也不会变成透明空面板。

**顺序修正（勘查后）**：原计划先做音频播放控制面板，但核对代码后该面**没有可采样的兄弟子树**——展开态面板的背景是面板自身 `ComposeView` 内绘制的封面氛围层（`CoverAtmosphereBackdrop`），崩溃态胶囊是 `TYPE_APPLICATION_OVERLAY` 独立窗口（跨窗口无法录制 RenderNode）。要接折射必须把氛围层重构为真实 View 兄弟节点，属于「面板整体玻璃化」，归 P4。视频控制条同理（画面由 GSY `VideoPlayer` 内部的 Surface 渲染，控制条由该库自有布局承载）。书籍详情信息卡是滚动内容面板、底色 87% 不透明（`book_info_frost` 为 `#DD…`），换玻璃会直接降低正文可读性且需与 `vw_bg` 的 50% scrim 做叠加补偿，也归 P4。故 P3 首个（也是本阶段唯一）落点为阅读页底部菜单，其余内容域表面在 P4 统一评估。

**已知边界**：P3 当轮只做了底部菜单，顶部菜单随后在 P4 补齐（同一采样源与同一材质入口，见下）；EPUB 直读、solid 档、API<33、低内存与墨水屏完全保持原观感。

### P4 · 面板与弹层

**已实施：主界面侧边导航面板（`MainActivity`）**

- **采样源**：`side_navigation_panel` 与 `content_container` 是同一窗口内的兄弟子树，面板底色换成玻璃后直接复用底栏那棵 `content_container`，不新增第二套采样源判定；面板与底栏一样走 `StableLiquidGlassView`。
- **面板整体玻璃化**：玻璃层是面板的**第一个子 View**（`MATCH_PARENT`）；玻璃激活时面板底色改透明、1dp 描边改挂玻璃层 `foreground`，标题卡与选中行改半透明白/黑（与自定义侧边背景图同一分支），面板内控件不再单独采样。
- **scrim 叠加补偿**：面板外被 `side_navigation_scrim` 压暗 0.42，而玻璃采样的是未压暗的 `content_container`，所以玻璃之上加一层纯黑 0.42 的补偿 View，`alpha` 与 scrim 同动画同步（开 180ms / 关 160ms），面板内外亮度一致。常量 `SIDEBAR_SCRIM_ALPHA` 同时供 scrim 与补偿层，避免两份数值漂移。
- **否决条件（纯函数）**：`sidebarGlassSourceAllowed(hasCustomBackground, sourceAvailable)`——配置自定义侧边背景图时面板最终外观由那张图决定、玻璃层会被整块盖住，直接不接；其余仍交给 `contentLiquidGlassSpec`（solid 档 / API<33 / 低内存 / 墨水屏 / 无采样源）。JVM 单测 3 例锁表。
- **绑定时机**：只在抽屉打开期间绑定（`openSideNavigation` 的 `doOnLayout`），收起动画结束（220ms）后解绑——关闭期间不付每帧整树录制开销，与「菜单可见期绑定」同一纪律。
- **测量安全**：面板走常规布局，玻璃层随面板一起测量，不存在 P3 的 `LayoutParams(0,0)` 场景；但「让出底色」仍加同一道闸——纯函数 `sidebarGlassFillYielded(active, glassWidth, glassHeight)`，只有玻璃层量到有效尺寸后面板才转透明（由玻璃层的布局回调触发一次重算），玻璃链路失败也不会出现透明空面板。JVM 单测 3 例锁表。

**已实施：`ModernActionPopup` 弹层整体玻璃化**

- **覆盖面**：`ModernActionPopup` 是弹层统一宿主——发现页 `discoverMorePopup` / `tagFilterPopup` / `modernMenuPopup`、各页行菜单与 `showFromMenu` 全部经它出图，因此这一处接入同时落掉 P4 的「发现页弹窗」一项。
- **采样源**：弹层是挂在 `anchor.rootView`（DecorView）上的全屏覆盖 `ComposeView`，`android.R.id.content` 子树与它是同一窗口内的兄弟分支，取它作采样源；不用 `host` 自身，也不含弹层自己，不存在自录制。源按 `host.findViewById(android.R.id.content)` 解析并校验 `isShown && width/height > 0`——独立 `PopupWindow` / 弹窗窗口里找不到该 id 时自然退回 null，弹层保持实底。
- **无 scrim，因此无需补偿层**：与侧边面板 / 对话框不同，弹层背后没有压暗层，玻璃采样亮度即最终亮度；这也说明「先定 scrim 补偿再接入」的约束只约束带 scrim 的宿主，不构成对弹层的阻塞。
- **面板整体玻璃化**：玻璃层与面板 `Surface` 共享同一套 `offset` / 宽高 / `graphicsLayer`（`alpha`、`transformOrigin`、0.96→1 缩放、±6px 位移），逐帧同形；玻璃就位后面板 `color` 改 `Transparent`，圆角由玻璃层 `setCornerRadius(panelRadius)` 承担。菜单行经 `LegadoMiuixChoiceRow(translucentSurface = true)` 让出行底色（仅选中态保留 0.14 强调色），否则默认 `surfaceVariant` 在默认 `dialogAlpha=100` 下近似不透明、玻璃只剩行间缝隙可见。
- **测量安全（纯函数）**：玻璃层是面板首帧测量回填尺寸后才挂上的，所以「让出底色」必须同时满足「玻璃可用」与「面板已测量」——纯函数 `overlayGlassFillYielded(glassAvailable, panelWidthPx, panelHeightPx)`，JVM 单测 3 例锁表（含首帧 0×0 回归锁）。未满足时面板保持实底、不挂玻璃层，避免弹出透明空面板（与阅读页菜单 1.25.11 同一失败形态）。
- **绑定时机**：玻璃层随 `AndroidView` 组合进树时 `bind`，弹层移除即 `onDetachedFromWindow` → `StableLiquidGlassView.removeGlass()` 自动解绑；收起动画期间（150ms / 墨水屏 0ms）玻璃与面板一起淡出，不存在「面板已移除仍在录制」的窗口。
- **降级**：`contentLiquidGlassSpec` 统一判定 solid 档 / API<33 / 低内存 / 墨水屏 / 无采样源，命中任一则 `glass == null`，弹层维持原有实底表面（含行色）；自定义主题、日间夜间均不新增分支。

**已实施：阅读页顶部菜单玻璃化（`ReadMenu`）**

- **采样源复用**：顶部菜单与底部菜单同属 `read_menu`，采样源仍是阅读页正文 `read_view`（同一兄弟子树、同一 `selfOrAncestorContains` 守卫）。两个玻璃层（`topGlass` / `bottomGlass`）在同一份 `refreshMenuGlass()` 里解析一次源与 spec，任一为 null 时两层一起退回实底，不会出现「下半截玻璃、上半截实底」的材质分叉。
- **方形而非圆角**：`ReadMenuTitleBar` / `ReadMenuActionBar` 本身就是贴边全宽的矩形 `Surface`；玻璃层只有单一半径 API，加圆角会在屏幕顶边两角挖出缺口。因此顶部玻璃 `setCornerRadius(0f)`（不裁剪），`glassPanelRect(..., cornerRadiusPx = 0f)` 不向下延伸底边——与既有矩形顶栏形状逐像素一致。
- **几何与让出底色**：Compose 顶栏 `onGloballyPositioned` 把窗口坐标交给 `updateTopGlassGeometry()`，玻璃层按目标尺寸 `EXACTLY` 测量并布局；`glassPanelFillYielded(topGlassActive, topGlassGeometried)` 未满足时顶栏保持 `style.surface`，玻璃链路失败不会得到透明空顶栏（与底部菜单 1.25.11 修复同一闸）。
- **绑定时机**：与底部菜单同步——`runMenuIn()` 绑定、`runMenuOut()` 动画结束后解绑，阅读过程中不付每帧整树录制的开销。

**待接入项的结论（均为「维持实底」，不是延后）**

- **键盘工具条 `KeyboardToolPop`**：独立 `PopupWindow` 窗口，窗口内找不到 Activity 的 `android.R.id.content`，无法跨窗口录制 RenderNode；其自身又是不透明卡片底。维持实底，除非把它重写为同窗口覆盖层（会改动输入法弹层生命周期，收益不抵风险）。
- **套件管理预览**：面板背景是自身 Compose 绘制（可带 `panelImage`），其下方没有可采样的真实内容子树；接折射只会画出被面板遮住的壁纸（2.2 的错误管线）。维持实底。
- **音频 / 视频控制面板**：展开态背景是面板自身 Compose 内的 `CoverAtmosphereBackdrop`，崩溃态胶囊是 `TYPE_APPLICATION_OVERLAY` 独立窗口；接玻璃必须先做「氛围层重构为真实 View 兄弟节点」的面板级改造，属于独立项目。维持实底。
- **书籍详情信息卡**：底色 87% 不透明且叠 50% scrim，换玻璃会直接降低封面文字可读性。维持实底。
- **带 scrim 的通用对话框（含 `SourceLoginDialog`）**：对话框在自己的窗口绘制，activity 侧无法采样；且玻璃采到的是未压暗内容，需先定 scrim 减淡 / tint 提亮补偿（侧边面板 0.42 补偿层是参照）。维持实底。

## 5. 约束与风险

- **无新增用户可见偏好键**：所有阶段消费现有档位键与 `GlassMaterialPreset`；`tools/check_settings_mapping.py` 必须 PASS。
- **行内禁令不变**：RecyclerView 行内一律 drawable 路径；`StableLiquidGlassView` 只用于固定位置浮层。
- **降级链不变**：新接入面自动继承四档降级与设备判定，不写第二套判断。
- **性能边界**：单表面面积与底栏同量级可接受；列表行（书架卡片、RSS 卡片）属于高数量表面，不做逐行玻璃化。
- **验证协议**：`./gradlew :app:testAppDebugUnitTest --tests "io.legado.app.ui.main.*" --tests "io.legado.app.ui.config.*" --tests "io.legado.app.ui.glass.*"`、`:app:assembleAppDebug`、`python3 tools/check_settings_mapping.py`；真机观感由安装 debug 包核验。
