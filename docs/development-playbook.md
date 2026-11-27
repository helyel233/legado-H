# legado-H 开发经验与规则沉淀

> 来源：项目 `.workbuddy/memory/` 工作日志（2026-10-31 ~ 2026-11-24）、`MEMORY.md`、`docs/change-safety.md`、`docs/release-process.md` 及项目级 Skill `legado-change-safety`。每条规则都对应一次真实返工或用户明确指令；文末给出原始文档索引。
>
> 规则优先级：**用户最新明确指令 > 本文件的现行约定 > 历史流程文档**。新旧约定冲突时，按下方「规则生效时序」处理，不按文件字面照抄旧条款。

---

## 0. 规则生效时序（先读这一节）

| 时间 | 变化 | 现行状态 |
|---|---|---|
| 2026-11-17 | 一个月返工复盘沉淀出 R1–R5 改动安全网（`docs/change-safety.md`） | 仍然有效 |
| 2026-11-20 | 曾要求「每轮必须打包装机到 md5 核对为止」，作为固定流程 | **已作废** |
| 2026-11-22 | 用户明确：默认只用文字交付；`评估` 只评估；出包与 review 权归用户 | **现行最高优先级** |
| 2026-11-22 之后 | 11-22/23/24 的交付均止于文字、上传与校验，未做 adb | 与现行约定一致 |

**冲突消解**：`docs/change-safety.md` 的 R1 五步闭环与 `.workbuddy/skills/legado-change-safety/SKILL.md` 的「交付前清单」写有出包、装机、截图步骤；按 2026-11-22 约定，这些步骤**仅当用户明确提出要包 / 要设备验证时执行**。日常交付不执行，也不得声称已做实机验证。

---

## 1. 与用户协作的硬规则

| 规则 | 具体要求 | 来源 |
|---|---|---|
| `评估` 只评估 | 用户说「评估 / 校验 / 检查 / review」时，实跑门禁、编译、单测，读代码核对声称，输出证据与问题清单；**不改代码、不修缺陷、不补测试、不提交、不打包**，缺陷只写进结论等用户决定 | 2026-11-22 用户强制 |
| 默认文字交付 | 改造与验证结果只用文字说明；不自行打 debug APK、不运行 adb 安装/调试/截图；需要出包时用户会说 | 2026-11-22 用户明确 |
| 出包权 / review 权在用户 | 用户没说要包就不出包；用户没说要 review 就不做代码 review | 2026-11-22 用户明确 |
| 不伪造验证 | 设备离线时如实标「待装机」；没有 adb 证据不得声称「实机已验证」；报告中的数字、覆盖率、通过状态必须来自实跑结果 | 2026-11-13 / 11-20 / 11-24 |
| 先确认问题定义 | 接到诉求先判断「是否把症状当原因、手段当目标」，不默认问题定义正确；需求口径有歧义时先对齐再动手 | 2026-11-17 / 11-19 |
| 破坏性动作先确认 | 删除分支、清理 hprof 等需用户明确授权；清理优先用系统废纸篓而非 `rm` | 2026-11-17 / 11-22 |
| 用户占用设备即停手 | 用户开始使用设备时立即停止一切设备操控 | 2026-11-19 |
| 汇报口径精确 | 只说「过滤后的子集通过」，不说「全量全 PASS」；术语前后一致；英文键名配中文说明；不用含糊表述代替触发条件、影响组件与根因 | 2026-11-20 / 11-23 / 11-24 |

---

## 2. 改动安全网 R1–R5

完整版见 `docs/change-safety.md`，可执行版见 `.workbuddy/skills/legado-change-safety/SKILL.md`。五条来自 2026-10-17 ~ 11-17 的真实返工复盘：

| 编号 | 根因 | 典型事故 | 正确做法 |
|---|---|---|---|
| R1 | 代码改完 ≠ 用户看到 | 玻璃三页 5 轮「修了没变化」；27 个改动停留在未提交工作区，从未进入用户安装的包 | 默认文字交付并如实标注验证边界；**明确要包时**走完整闭环：编译 → 定向单测 → `assembleAppDebug` → 装机核对包身份 → 渲染类截图取证 |
| R2 | 同一能力多套实现只改其一 | A 套 AGSL `RowGlassLiquidRenderer` 与 B 套手写 `GlassFrostDrawable` 并存；`useComposeBookInfo=false` 死开关；XML/Compose 双轨只改一条 | 开工先 `grep` 同类实现；玻璃只接 `ui/glass/` 唯一管线、参数唯一来源 `GlassMaterialPreset`；多渲染路径按分流函数逐条枚举并全部改到位 |
| R3 | 先调参不找根因 | `0xff000000.toInt() shl 24` 使 alpha 恒 0，软件玻璃静默失效一个月；排查却一直在调模糊强度 | 顺序固定：先验产物是否存在且可见 → 再验管线是否走对（采样源、降级回退、AGSL 日志） → 最后才调强度/观感 |
| R4 | 用数值反推状态 | 用「强度 == 70」反推预设，被主题包启动时的 60 覆盖误判「自定义」；用异步截图是否为空反推渲染管线，观感随时序跳变；`fullScreen()` 中 `setDecorFitsSystemWindows(true)` 与 `LAYOUT_FULLSCREEN` 矛盾 | 状态用独立布尔/枚举键（如 `glassPresetMatched`、`floatingChrome`）；多入口共享键先列清覆盖写入点并写进 KDoc；粘性窗口 flag 同向设置；观感由档位唯一决定 |
| R5 | 一坨提交难回滚 | 同一改动提交两次；单 commit 混入 81 个文件；回滚因果散落在 message 里 | 功能与「定稿日志」分笔；大改动拆「纯函数抽取 → 接线 → 收口」；回退 commit 写明回退内容、原因、仍被引用的产物 |

R3 的反模式已固化为 CI 门禁：`alpha shl 24` 合法，`<带 alpha 的常量> shl 24` 非法（`tools/check_glass_governance.py`，已接入 `.github/workflows/verify.yml`）。门禁本身做过反向注入验证：注入错误写法正确 FAIL 1 处，合法写法零误报。

---

## 3. 可执行清单

### 3.1 改动前（命中任一触发条件就跑）

触发条件：改 `ui/glass/`、窗口/系统栏 flag（`setDecorFitsSystemWindows` / `LAYOUT_FULLSCREEN` / `statusBarColor`）、`PreferKey` / `AppConfig` 偏好键、XML/Compose 并存页面、位运算工具（`BitmapUtils` / `ColorUtils`）。

1. `grep` 是否已有同类实现，能复用就不新建第二套。
2. 查分流函数（如 `BookInfoNavigator.targetClass()`、`BookInfoPageStyle.fromKey`）列出所有存活渲染路径。
3. 新状态是否可被主题包、启动迁移、全局开关覆盖；能覆盖就设独立状态键。
4. 检查所有粘性窗口 flag 是否同向。
5. 新机制是否同时具备写入端（UI 入口）与消费端；缺写入端的机制是休眠机制，不会出现效果。
6. 确认活跃配置：夜间/日间资源包、`bookGroupStyle` 等设备偏好决定实际页面分支，先查再改（2026-11-18 曾在 `day/` 改三轮无效）。

### 3.2 交付前（默认、纯文字交付）

```bash
./gradlew :app:compileAppDebugKotlin --rerun
./gradlew :app:testAppDebugUnitTest --tests <类名> --rerun --offline
python3 tools/check_settings_mapping.py
python3 tools/check_r58_resources.py
python3 tools/check_glass_governance.py
python3 tools/check_compose_theme_coverage.py
python3 tools/check_ui_layout_tokens.py        # report-only
git diff --check
```

关键口径：

- `compileAppDebugKotlin` 与单测**分开跑**并单独加 `--rerun`；与单测组合跑时 compile 会 UP-TO-DATE，`--rerun` 不覆盖依赖任务（2026-11-22 实测）。
- 定向单测的类名先用 Glob/Grep 确认存在；结果从 `app/build/test-results/testAppDebugUnitTest/*.xml` 统计类数/用例数，不采信 gradle 汇总行。
- 编译偶发无错误行的 BUILD FAILED 时，重跑同命令；以产物时间戳与类文件齐全判断是否真编译（2026-11-24）。
- 运行期才暴露的断链靠静态门禁拦，新增设置项必须进搜索索引；提交前核对 `git status` 与 `git diff --cached --stat`（2026-11-17）。

### 3.3 仅当用户明确要包 / 要设备验证时

1. 按 3.2 完成编译、定向单测、静态门禁。
2. 按 R5 分笔提交；推送用直连 `env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy git push`（代理曾对 github.com 连续 502）；推后确认 `origin/main...HEAD` 为 0/0。
3. `./gradlew :app:assembleAppDebug` 出包 → `adb install -r` → 本地与设备 `base.apk` 的 md5 比对 + `dumpsys package` 的 versionCode/versionName 核对。
4. 渲染/视觉类改动补 `exec-out screencap` 截图取证；冷启动首帧可能全黑，等几秒重截。

设备细节：`adb` 全路径 `/Users/zego/Library/Android/sdk/platform-tools/adb`（`which adb` 为 not found）；该 adb 无 `monkey` 子命令，启动 app 用 `cmd package resolve-activity --brief <pkg>` + `am start -n`；vivo V2417A 装包会弹「外部来源应用」确认框，后台跑 install 后按截图点「继续安装」；`pm path` + `md5sum` 比对包身份比 versionName 可靠（曾出现 versionName 字符串不变）。

---

## 4. 门禁与验证口径

| 工具 | 作用 / 口径 | 备注 |
|---|---|---|
| `tools/check_settings_mapping.py` | 设置项四处映射铁律：`ConfigTag` 常量 → `ConfigActivity.when` 分发 → `breadcrumbOf` 父级同源 → `buildSections` 行 key + `handleRowClick` 跳转 → `SettingsSearchIndex.pages` 的 `PageSource(ownerKey=一级行 key)`；断链漏改不报编译错，运行期静默 | 特殊行白名单按需补；11-24 曾因 eInkMode 特殊行 FAIL |
| `tools/check_r58_resources.py` + `tools/r58_baseline.json` | R58/R59 文本节点与 locale 基线（629 条节点、10 条显式豁免） | 反面教训：默认 `values` 应为英文、`values-zh` 为中文，曾写反 |
| `tools/check_glass_governance.py` | alpha 位运算反模式硬门禁 | 已接入 `.github/workflows/verify.yml`，位于设置映射检查步骤之后 |
| `tools/check_compose_theme_coverage.py` | Compose 主题覆盖，136/136；口径为 161 个 `setContent` 中 112 处直接包 `LegadoComposeTheme`、49 处经 5 个收口点承接 | 「100%」是在收口点定义下成立，不是字面每个 `setContent` 都包 |
| `tools/check_ui_layout_tokens.py` | UI 布局 token 扫描，report-only，`--strict` 现状必红 | 灵敏度有限（±3 行豁免窗口等），仅作线索 |
| `tools/architecture_guide.py` | 从源码实时生成架构导读到 stdout | 刻意不落盘静态 md、不新增 CI 门禁，避免文档腐烂 |
| `.github/workflows/verify.yml` | CI 已接入 Settings mapping、r58、glass 三项 | 发版前自跑全套，不采信提交自报 |

**测试口径的三条反面教训**（2026-11-20 ~ 11-23）：

1. 「全量单测全 PASS」不实：实际 48 红 / 19 类，均为 `ClassNotFoundException: android.app.ActivityThread`（本地 Robolectric 缺匹配 SDK，targetSdk=36）。只报过滤后的子集。
2. 「红数/红类一致 = 无回归」的对照法失效。改用 `git worktree` 拉干净 HEAD 复现，或 grep 红类是否 import 本轮改动类（零命中才算证伪）。
3. 「240/240」「474 = 全量」等口径遮掉大量排除用例；统计必须给出分母和排除项。

定位 gradle 测试挂起：`jstack` 抓工作进程栈最快。2026-11-23 实测 `CoverSlotRepositoryTest` 用裸 `runBlocking` 在 Robolectric 主线程调用仓库，仓库 `withContext(IO)` 再跳 Main（looper paused）自死锁；沙箱与非沙箱同挂，先区分环境问题与测试自身死锁。

---

## 5. 反复出现的根因与修复范式

| 场景 | 错误做法 / 现象 | 经确认的修复范式 | 日期 |
|---|---|---|---|
| 顶栏圆角「未生效」 | 无代码写 `floatingChrome=true`；inset 只喂 padding；书架 B 系漏改；设备夜间模式却改 `day/` 资源 | 同时补写入端 UI、消费端几何、双轨覆盖；QA 先确认活跃包与日夜模式 | 11-18 |
| 材质跳变 | `BookInfoBackdropSurface.glassFrost()` 用异步 `backdrop: Bitmap?` 是否有空反推管线；Glide 300ms crossfade 与缩略图竞速 | 恒返回 `GlassLiquidBackdropDrawable`，backdrop 退化为采样输入；`.dontAnimate()` + `isFirstResource` 守卫；共享底图 + `draw()` 期实时采样 | 11-18 / 11-19 |
| 同一属性多写者 | `titleBar.background` 被 chrome 几何与 `opaqueRoundedStroke(...,0f,...)` 同时写 | 新增 `TitleBar.applyPageOwnedSurface(face)` 收为单一写者 | 11-18 |
| 系统栏 flag 矛盾 | `setupSystemBar()` 在 `setContentView()` 之前调用，窗口 flag 被重置 | 调整调用顺序；`setDecorFitsSystemWindows` 与 `LAYOUT_FULLSCREEN` 由 `overlapStatusBar` 单点同向决定 | 11-16 / 11-17 |
| 视图控件定位 | 看图猜控件 | 用截图文案反查唯一 `R.string`，再定位类（如「筛选发现源」→ `SourceSelectDialog`） | 11-13 |
| 三方库行为判断 | 凭旧版本记忆断定 `TabLayoutMediator` 不响应 DiffUtil | 反编译 `material-1.13.0` aar，确认六个回调全覆盖后撤销误判 | 11-23 |
| 备份导出 | 只复制到暂存区未进 `zipSources` 显式白名单，导出静默空转且下次备份 `delete(backupPath)` 丢弃 | `UiPackageBackup` 纯 JVM object 持有四目录常量单一真相源；白名单显式登记 | 11-17 |
| 命令行陷阱 | macOS BSD `grep` 不支持 `\|`，静默 0 命中 | 用 Grep 工具或 `grep -E` / `rg` | 11-17 |
| 汇报失真 | 「沙盒锁文件无法编译」「零主线程磁盘 IO」「改动文件零警告」均与事实不符 | 以实跑输出和逐条代码核对为准；区分「既有警告」与「本次新增」 | 11-24 |
| 审查结论误报 | 审查 Agent 报的 eInk 双入口、`coerceIn` 崩溃经核实为既有模式 / 有 `isEmpty` 守卫 | 审查结论逐条回源码证伪后再写入问题清单 | 11-24 |

---

## 6. 已确认的架构结论

- **主线**：`main` 是唯一主线；`v5` 于 2026-10-16 冻结在 `876e8781e`，不合并不发版不开发，仅作归档参考；2026-11-22 经用户确认删除本地与远程分支。versionCode 与 v5 已脱钩，无需改基线。
- **玻璃**：用户认可 A 套 AGSL 管线，B 套手写 `GlassFrostDrawable` 已删除，只保留 `glassFrostSurfaceRgb` 迁入 `GlassSurfacePalette.kt`。观感由档位唯一决定，与运行时序无关。
- **顶栏三族**：A 系 `MainTopBarView`（4 主 Tab），B 系 `TitleBar`（59 layout），C 系 Compose 自建（15 个形态）。A+B 覆盖顶栏包语法全部页面；C 系维持既定形态，不消费顶栏包语法。`AppManagementScaffold` 的 `cornerRadiusPx=0f` 是有意设计（贴边全宽），不是死参数。
- **顶栏几何单一真相源**：`lib/theme/TopBarChromeSpec.kt` 纯函数层；半径唯一取 `TopBarConfig.cornerRadius`；严禁用 `cornerScale>0` / `radius>0` 反推是否悬浮。
- **UI 架构方案**：已收口至 v4 基础层与 v4.1 双包体系（`docs/ui-architecture-v4-plan.md`）。v4 的两个 Kill Switch 单一写入点在 `AppearanceKitActivity`；Scope Token 走纯函数 `resolveScopeOverrides`；recreate 由 `WindowSnapshotTransitionController` 包裹；16 个新偏好键集中在 `PreferKey.kt`。
- **Compose/XML 并存**：136 个真 Compose `setContent` 文件；`LegadoComposeTheme` 不产生布局节点、不画背景 Surface，只提供 `MaterialTheme + LocalTextStyle + LocalLegadoTokens`。墨水屏下 token 会把 accent→黑、primary/card/bottomBar→白，属于已记录的行为变化。
- **系统栏**：`targetSdk 36` / Android 16 上 `windowOptOutEdgeToEdgeEnforcement` 已禁用，`statusBarColor` / `setDecorFitsSystemWindows` 被废弃或失效；真实落地在 `ActivityExtensions.applyGlobalStatusBarInset()`；阅读器与 EPUB 预览覆写 `applyGlobalStatusBarInsetEnabled=false` 自管。
- **偏好键**：独立布尔/枚举状态键取代数值反推是既定范式（`glassPresetMatched`、`floatingChrome`、`readBook.msgIsError`、eInk 的 `lastNonEInkThemeMode`）。

---

## 7. 提交、推送与发版

- **提交粒度**：按 R5 分笔；功能代码与 `CHANGELOG.md` / `app/src/main/assets/updateLog.md` 定稿日志分开。真正无法安全强拆的大改动（如共享 strings.xml 多 locale）可合一笔，但要在 commit message 写明原因。
- **推送**：代理对 GitHub 曾连续 502，用 `env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy git push` 直连；推后核对 `origin/main...HEAD` 为 0/0、`git ls-remote` 校验 tag、工作区干净。不要 force-push `main`。
- **版本**：`versionCode = 29860000 + git rev-list --count HEAD`（`.legadoh/build-signed.sh:13`）；发版细则以 `docs/release-process.md` 为唯一规范，该文件与近期 CHANGELOG 的版本号写法有演进差异时以最新定稿为准。
- **发版链路**：先提交并推送定稿与 tag → `.legadoh/build-signed.sh <数字版本号>` 出正式包 → `./gradlew --stop` 后单独出 debug 包（release 与 debug 严禁并行）→ `apksigner verify --print-certs` 与包内 `assets/updateLog.md` 校验 → `.legadoh/create-release.sh <版本号>` 上传 → 本地与远端 SHA-256 比对。
- **发版后**：仓库根只留当前版本双包；Release 正文只含单章节；无 CI 发布渠道，不恢复 Gitee 相关代码。

---

## 8. 原始文档索引

| 文档 | 内容 |
|---|---|
| `docs/change-safety.md` | R1–R5 改动安全网完整版（含真实事故与代码反例） |
| `docs/release-process.md` | 发版唯一规范：版本规则、双包构建、上传、纠错 |
| `docs/ui-architecture-v4-plan.md` | UI 架构 v4 与双包体系定稿方案 |
| `docs/glass-appwide-adoption-plan.md` | 玻璃全 App 接入方案 |
| `.workbuddy/skills/legado-change-safety/SKILL.md` | 改动前 / 交付前可执行检查清单（交付清单按本文第 0 节的现行约定使用） |
| `.workbuddy/memory/YYYY-MM-DD.md` | 逐日事实底稿；规则冲突时以更新日期最新的记录为准 |

> 本文是经验索引，不替代上述规范原文；新增规则时先更新对应原始文档与工作日志，再同步本文。
