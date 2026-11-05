# 软件使用体验优化 PRD

> 项目：LegadoH（开源阅读类 Android 应用）
> 范围：应用整体（书架 / 发现 / 阅读 / 设置等页面）
> 角色：产品经理 · 许清楚
> 性质：产品需求文档（不涉及实现）

---

## 1. 项目信息

- **Language**：中文
- **Project Name**：`ux_optimization`
- **原始需求复述**：围绕应用整体使用体验，沿三条主线做系统性优化——①视觉与交互一致性 ②稳定与容错 ③流畅度与性能。

## 2. 产品目标（可量化验收）

| 编号 | 目标 | 量化验收标准 |
| --- | --- | --- |
| G1 | 视觉与交互一致 | 设置 / 弹窗 / 卡片等复用同一套规范（`AppSettingPalette` / `AppDialogStyle`），无「同屏一半新样式一半旧样式」的割裂；顶栏/底栏毛玻璃与文字投影在背景图模式下全站表现一致 |
| G2 | 稳定与容错 | 用户可见异常（导入失败 / 加载失败 / 相对时间）均非技术化且可理解；不再向用户暴露 `localizedMessage` / 堆栈 / 硬编码中文碎片 |
| G3 | 流畅与性能 | 高渲染开销路径（毛玻璃 / 列表滚动）不产生主线程卡顿；配置解析 / 数据加载不出现可感知的重复计算或等待 |
| G4 | 全局联动一致 | 事件总线（`EventBus`）发布订阅成对、改设置即时生效、设置项全部可搜索命中 |

## 3. 用户故事

### US1（重视观感一致的用户）
> 作为**在多个页面之间来回切换的用户**，我希望书架、发现、阅读、设置的配色、圆角、弹窗风格是同一套观感，不要出现同一功能在不同页面样式不一致的情况。

**验收标准**：所有 Compose 页面统一走 `AppSettingPalette` / `AppDialogStyle`；背景图模式下各页面文字投影与半透明衬底表现一致；深浅色切换无样式残留。

### US2（遇到导入/加载失败的用户）
> 作为**导入书源、加载发现列表失败的用户**，我希望看到「哪里出问题、可以怎么办」的友好提示，而不是一串 `ImportError:xxx` 或技术异常原文。

**验收标准**：导入失败、列表加载失败、文件打开失败均展示非技术文案与可重试/返回入口；不再出现 `e.localizedMessage` 直接上屏。

### US3（关注性能的老用户）
> 作为**在低端机或墨水屏上使用的老用户**，我希望毛玻璃、滚动列表这类重特效不影响操作流畅度，宁可牺牲一点观感换取流畅。

**验收标准**：主线程无明显长任务；毛玻璃在低端设备可自动降级；列表滚动丢帧率可控。

### US4（看时间戳的用户）
> 作为**英文系统用户**，我希望评论时间、更新时间等「3秒前 / 5分钟前」这类相对时间随系统语言显示，而不是永远显示中文。

**验收标准**：`toTimeAgo()` 等相对时间随 locale 显示对应语言；英文系统下无中文碎片。

### US5（改设置后立即想见效的用户）
> 作为**在设置里改了布局/显示项的用户**，我希望切回页面立即生效，而不是「设置页改了、页面还是旧样子」。

**验收标准**：`UP_PAGE_LAYOUT` / `UP_MANGA_DISPLAY` / `WEB_SERVICE` 等事件发布订阅成对、改后即时重刷；搜索任意设置项可直达定位。

## 4. 需求池

### P0（必须做）

#### P0-1 用户可见异常信息去技术化
- **现状痛点**：`ui/` 层存在 **426 处** `localizedMessage` 引用，其中大量直接上屏——如 `ExploreFragment.loadDiscoverBooks()` catch 分支 `binding.tvDiscoverEmpty.text = e.localizedMessage ?: getString(R.string.unknown_error)`（L3577）；`FileAssociationViewModel.kt` L60 `"无法打开文件\n${it.localizedMessage}"`；`VideoPlayerViewModel.kt` L93 `"$name click error\n${it.localizedMessage}"`；`Import*ViewModel` 系列 `"ImportError:${it.localizedMessage}"`。技术异常原文对普通用户无意义且泄露内部实现。
- **目标**：用户可见异常统一映射为友好文案（G2）。
- **建议方案**：建立统一的 `Exception → 用户文案` 映射层（网络失败 / 超时 / 格式错误 / 源失效 / 未知），在 UI 展示层拦截 `localizedMessage` 直出；日志仍保留原始异常，仅用户可见层脱敏。
- **验收标准**：UI 层不再出现 `localizedMessage` 直接上屏（日志/AppLog 除外）；四类异常均有非技术文案与可重试入口。
- **涉及文件/模块**：`ExploreFragment.kt`、`FileAssociationViewModel.kt`、`VideoPlayerViewModel.kt`、`ui/association/Import*ViewModel.kt`、`OnLineImportActivity.kt` 等（全量 426 处扫描）。

#### P0-2 相对时间硬编码中文本地化
- **现状痛点**：`TimeUtils.kt` L5-39 `Long.toTimeAgo()` 中「秒/分钟/小时/天/周/月/年」与「前/后」全部硬编码中文，英文系统下评论时间、更新时间永远显示中文（记忆 R1-R59 已登记为既有遗留）。
- **目标**：相对时间随系统 locale 显示（G2）。
- **建议方案**：将时间单位与前后缀抽入 `strings.xml`（中英各一份），用 `appCtx.getString` + `%1$d` 位置参数拼接；保持数值换算逻辑不变。
- **验收标准**：英文系统下 `toTimeAgo()` 输出英文相对时间；中文系统行为不变；无 `%` 格式化符错配。
- **涉及文件/模块**：`TimeUtils.kt`、`strings.xml` / `strings_misc.xml` 等资源。

#### P0-3 毛玻璃主线程开销治理
- **现状痛点**：`ExploreGlassBackdrop.FrostedGlassDrawable.draw()` L296-329 每次绘制都调用 `host.getLocationOnScreen(location)` + `root.getLocationOnScreen(rootLocation)` 并重算 `BitmapShader` matrix（L302-315），在滚动时逐帧触发；`ExploreAdapter.convert()` L101-104 每行 bind 都调用 `ExploreGlassBackdrop.rowBackground()`。虽然重解码/软件模糊已放 IO 且按 key 去重（`inFlightKeys` / `pendingCallbacks`），但 `draw` 路径的逐帧位置采样仍是主线程开销点。
- **目标**：毛玻璃不拖累滚动帧率（G3）。
- **建议方案**：将 `getLocationOnScreen` 改为布局/滚动回调驱动的位置缓存，仅在位置真正变化时重算 shader matrix；对低端机 / 墨水屏（`isEInkMode` 已强制 level=0）提供自动降级或实底兜底。
- **验收标准**：开启毛玻璃时滚动 Jank 可控；位置缓存命中时不再逐帧 `getLocationOnScreen`；低端设备自动降级无异常。
- **涉及文件/模块**：`ExploreGlassBackdrop.kt`、`ExploreAdapter.kt`、`StableLiquidGlassView.kt`。

#### P0-4 事件总线发布订阅成对与即时生效兜底
- **现状痛点**：`EventBus` 已有 62 个常量，其中 `UP_PAGE_LAYOUT`（L77）、`UP_MANGA_DISPLAY`（L61）、`WEB_SERVICE`（L32）依赖成对的发布/订阅；但发现页布局实际靠 `onResume` 重读兜底而非事件（记忆 2026-11-05 已登记为「行为对但文档不完整」）。改设置后部分页面靠「重读偏好」而非事件，存在「改完切回不生效」的隐患。
- **目标**：改设置即时生效、事件订阅无漏配（G4）。
- **建议方案**：梳理 `UP_PAGE_LAYOUT` / `UP_MANGA_DISPLAY` / `WEB_SERVICE` / `NOTIFY_MAIN` 等关键事件的发布订阅对，统一「事件 + onResume 重读」双保险；补充事件 KDoc 说明实际订阅方。
- **验收标准**：关键布局/显示/服务类设置改后切回页面 ≤1 次重绘即生效；无悬空事件（有发无收）与漏订阅。
- **涉及文件/模块**：`constant/EventBus.kt`、`PageLayoutConfigFragment.kt`、`HomepageFragment.kt`、`RssFragment.kt`、`NetworkConfigFragment.kt`。

### P1（应该做）

#### P1-1 设计规范落地审计与补齐
- **现状痛点**：`SKILL.md` 定义了 `AppSettingPalette`（`AppSettingComponents.kt` L89-108、`rememberAppSettingPalette` L126-193）与 `AppDialogStyle`（`AppComposeDialogs.kt` L102-115、`rememberAppDialogStyle` L118-186）规范；但迁移是分阶段进行的，仍有部分页面/弹窗未接入规范，存在样式不一致。
- **目标**：全站 Compose 页面/弹窗统一规范（G1）。
- **建议方案**：静态扫描「自定义颜色/圆角/字号而非走 palette」的页面，逐页接入 `rememberAppSettingPalette` / `rememberAppDialogStyle` / `appSettingPanelBackground`；输出不一致清单。
- **验收标准**：新增/存量 Compose 页面均引用统一 palette；同屏不再出现新旧样式混用。
- **涉及文件/模块**：`ui/widget/compose/AppSettingComponents.kt`、`AppComposeDialogs.kt`、`ui/config/*`、`ui/main/*` 等。

#### P1-2 背景图可读性全站一致性
- **现状痛点**：`BackgroundReadability.kt` 提供 `imageBgTextShadow()`（L34）与 `imageBgScrimColor()`（L50），仅在 `ThemeConfig.hasUsableBgImage()`（`ThemeConfig.kt` L193）为真时生效；README 声称「书架、发现、目录、阅读记录、RSS、搜索等页面文字自动加投影与半透明衬底」，但覆盖面需核实，部分页面可能未接入导致复杂背景图下文字难辨。
- **目标**：背景图模式下全站文字可读性一致（G1）。
- **建议方案**：扫描未接入 `imageBgTextShadow` / `imageBgScrimColor` 的列表/卡片页，补齐接入；统一投影与衬底参数（深浅色自适应）。
- **验收标准**：README 所列页面在复杂背景图下文字均可读；未开背景图时观感不变（无投影/衬底）。
- **涉及文件/模块**：`BackgroundReadability.kt`、`ThemeConfig.kt`、书架/目录/阅读记录/RSS/搜索页。

#### P1-3 列表滚动性能基线化
- **现状痛点**：发现/书架/阅读记录等列表页使用 `LazyColumn` / `RecyclerView` + 瀑布流（`StaggeredGridLayoutManager` / `LazyVerticalGrid`），但无统一滚动性能基线；`ComposeLazyListFastScroller` / `ExploreShowWaterfallAdapter` 等路径存在重计算点（如 `syncDiscoverComposeState` 签名比对、封面预加载）。
- **目标**：滚动流畅度有基线可回归（G3）。
- **建议方案**：建立滚动帧率/Jank 回归基线（可用 Macrobenchmark / FrameTiming）；对列表项绑定做「内容签名 diff」避免无谓重组（延续 `discoverBooksSignature` 思路）。
- **验收标准**：60Hz 设备滚动 Jank ≤5%；无新增回归。
- **涉及文件/模块**：`ExploreModernListScreen.kt`、`ExploreFragment.kt`、`ExploreShowWaterfallAdapter.kt`、书架 Compose 列表。

#### P1-4 相对时间之外的零散硬编码中文收尾
- **现状痛点**：R1-R59 已将 Kotlin 中文字面量从 4509 收敛到 2490（候选 0），但 `TimeUtils.toTimeAgo()` 等「数据层持有用户可见文案」仍硬编码中文（记忆已登记）。此外 `FileAssociationViewModel.kt` L60 等既有「无法打开文件」中文 + 异常拼接混合存在。
- **目标**：用户可见文案零硬编码中文（G2）。
- **建议方案**：随 P0-1/P0-2 一并清理「数据层/工具层持中文文案」的遗留点，复用 `tools/i18n_batch.py` 的 classify 口径做回归验证。
- **验收标准**：`classify` 真实可见候选保持 0；英文系统下无中文碎片。
- **涉及文件/模块**：`TimeUtils.kt`、`FileAssociationViewModel.kt`、`tools/i18n_batch.py` 口径。

### P2（可以做）

#### P2-1 顶栏/底栏毛玻璃效果统一与稳定性加固
- **现状痛点**：`StableLiquidGlassView.kt`（L14-20）是为规避第三方 `LiquidGlass` 库 detach 后 crash 而封装的稳定壳，内部 `rebuildGlass()` L193 在失败时重建；但底栏/顶栏毛玻璃与发现页 `ExploreGlassBackdrop` 是两套实现，观感与参数映射需人工对齐。
- **目标**：毛玻璃观感统一、更稳（G1）。
- **建议方案**：抽统一「玻璃效果参数」描述（模糊强度 / 着色 / 描边 / 折射），供顶栏/底栏/发现页行共用；继续用稳定壳包裹第三方库并补异常兜底。
- **验收标准**：顶栏/底栏/发现页玻璃观感一致；detach/attach 场景无 crash。
- **涉及文件/模块**：`StableLiquidGlassView.kt`、`ExploreGlassBackdrop.kt`、`MainTopBarView.kt`。

#### P2-2 内置主题展示与预览一致性
- **现状痛点**：README 提到 Minecraft、明日香、诡秘之主、哆啦 A 梦等内置阅读主题；主题编辑/预览链路（`ThemeManageActivity.kt`、`ThemeConfig.kt` 约 1236 行）承载主题切换、背景图、毛玻璃强度、不透明度等，但预览与实装的映射需人工确认一致。
- **目标**：主题预览所见即所得（G1）。
- **建议方案**：统一主题预览与实装共用同一配置解析（`ThemeConfig` / `ThemeRuntimeKeys`），避免预览参数漂移。
- **验收标准**：预览效果与实装一致；切换主题无残留旧配色。
- **涉及文件/模块**：`ThemeConfig.kt`、`ThemeManageActivity.kt`、`ThemeRuntimeKeys.kt`。

#### P2-3 设置搜索全量可定位的持续保障
- **现状痛点**：`SettingsSearchIndex` 从 spec 生成（`SettingsSearchIndex.kt` L26-105），已实现「新增设置项自动可搜」；但依赖「`buildSpec` 只依赖 Context」契约，任一页面误触宿主会导致整页索引退化。
- **目标**：设置搜索长期稳定可定位（G4）。
- **建议方案**：在 CI 中加入 `check_settings_mapping.py` 与 `check_r58_resources.py` 回归；对新增设置页强制要求 `searchKeys` 别名齐全。
- **验收标准**：新增设置项自动进索引；无死结果；搜索命中可直达定位。
- **涉及文件/模块**：`SettingsSearchIndex.kt`、`tools/check_settings_mapping.py`。

## 5. UI / 交互说明

### 5.1 视觉一致性
- 所有 Compose 页面/弹窗统一 `AppSettingPalette`（页面色/卡色/分割线/强调色/圆角）与 `AppDialogStyle`（弹窗面/描边/圆角/字体）规范；页面背景色 = `palette.page`，卡片 = `palette.row`。
- 背景图模式：文字投影深浅自适应（浅字黑影、深字白影）+ 半透明衬底，未开背景图时零效果。

### 5.2 容错降级
- 异常统一展示「友好文案 + 重试/返回」，不出现 `ImportError:` / `localizedMessage` / 堆栈。
- 相对时间随 locale 显示。

### 5.3 流畅度
- 毛玻璃逐帧采样改为位置缓存驱动；低端机自动降级。
- 列表项绑定做内容签名 diff，避免无谓重组。

## 6. 待确认问题（需客户拍板）

1. **异常文案口径**：是否所有 `ImportError:` / `load error` 统一为「导入失败 / 加载失败，点击重试」，还是保留少量技术详情（如长按查看）供高级用户排查书源？
2. **毛玻璃自动降级的边界**：低端机/墨水屏是否接受「自动关闭/降级毛玻璃」换取流畅？降级触发条件是设备性能档还是用户手动档？
3. **相对时间格式**：英文 locale 期望「3s ago / 5m ago」还是「3 seconds ago / 5 minutes ago」的完整写法？
4. **视觉规范强制的范围**：是否要求存量未接入 `AppSettingPalette` / `AppDialogStyle` 的页面在本轮全部补齐，还是仅对新页面强制、存量渐进？
5. **背景图可读性覆盖面**：README 所列页面（书架/发现/目录/阅读记录/RSS/搜索）之外的页面（如书源编辑、导入弹窗）是否也要在背景图模式下加投影/衬底？
6. **事件联动改造幅度**：改设置即时生效是否统一改为「事件 + onResume 重读」双保险，还是维持现状「重读兜底」为主？
7. **性能回归基线**：是否引入 Macrobenchmark / FrameTiming 作为 CI 门禁（成本较高），还是仅做开发期手动回归？
8. **第三方 LiquidGlass 库的取舍**：是否继续维护 `StableLiquidGlassView` 稳定壳，还是考虑自绘玻璃效果替换第三方依赖？

---

### 附：关键代码佐证索引

| 结论 | 位置 |
| --- | --- |
| UI 层 426 处 `localizedMessage` | `grep -rn localizedMessage app/src/main/java/io/legado/app/ui/` |
| 现代模式原始错误直出 | `ExploreFragment.loadDiscoverBooks()` L3577 `binding.tvDiscoverEmpty.text = e.localizedMessage` |
| 文件打开失败中文+异常拼接 | `FileAssociationViewModel.kt` L60 `"无法打开文件\n${it.localizedMessage}"` |
| 相对时间硬编码中文 | `TimeUtils.kt` L5-39 `Long.toTimeAgo()`（秒/分钟/小时/天/周/月/年 + 前/后） |
| 毛玻璃逐帧位置采样 | `ExploreGlassBackdrop.kt` `FrostedGlassDrawable.draw()` L296-329，`getLocationOnScreen` L302-303 |
| 毛玻璃重活已 IO 化并去重 | `ExploreGlassBackdrop.kt` `backdrop()` L86-133（`inFlightKeys` / `pendingCallbacks`） |
| 液体玻璃稳定壳 | `StableLiquidGlassView.kt` L14-20（说明）+ `rebuildGlass()` L193 |
| 设计规范（页面/弹窗） | `AppSettingComponents.kt` L89-108（`AppSettingPalette`）、L126-193；`AppComposeDialogs.kt` L102-115（`AppDialogStyle`）、L118-186 |
| 背景图可读性 | `BackgroundReadability.kt` L22-51（`imageBgTextShadow` / `imageBgScrimColor`）；`ThemeConfig.kt` L193 `hasUsableBgImage` |
| 事件总线常量 | `EventBus.kt` 共 62 个 `const val`；`UP_PAGE_LAYOUT` L77、`UP_MANGA_DISPLAY` L61、`WEB_SERVICE` L32 |
| 设置搜索从 spec 生成 | `SettingsSearchIndex.kt` L26-105 |
| 国际化收尾与遗留 | 记忆 `2026-11-04.md` / `2026-11-05.md`（R1-R59、`toTimeAgo` 遗留） |
