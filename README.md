# LegadoH

[中文](README.md) · [English](English.md)

<p align="center"><img width="128" height="128" src="docs/legadoh_icon.svg" alt="LegadoH"></p>

<p align="center">
  <a href="https://github.com/helyel233/legado-H/releases"><img alt="Release" src="https://img.shields.io/github/v/release/helyel233/legado-H?label=release"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
</p>

<p align="center"><b>LegadoH</b> —— 可自定义书源与界面的 Android 阅读器，继承自「阅读 Archive」，血统上溯至 <a href="https://github.com/gedoor/legado">Legado（阅读 3.0）</a>。</p>

---

## 简介

LegadoH 不内置任何书籍或书源：网络书籍来自你自行添加的书源，本地书籍支持 TXT 与 EPUB。它在完整继承「阅读 Archive」与上游 Legado 能力的基础上，继续围绕**排版质量、外观体系、阅读工具与数据安全**做深度定制——原生与独立 EPUB 双排版引擎、页面模板、可挂载的外观包、段评与高亮规则、听书与多媒体、AI 助手，以及覆盖书源、书架、阅读配置与资源的完整备份。

当前版本 **2.0.1**（2.0 外观体系首个版本）。完整变更见 [更新日志](CHANGELOG.md)，安装包见 [Releases](https://github.com/helyel233/legado-H/releases)。

## 项目渊源

LegadoH 并非从零开始的项目，它完整继承了一条持续演进的分支血统：

```
gedoor/legado（阅读 3.0）
  └─ Luoyacheng/legado
       └─ Rimchars/legado（「阅读 Archive」）
            └─ helyel233/legado-H（LegadoH，本项目）
```

- **继承自阅读 Archive**：独立 EPUB 排版引擎、页面/加载模板体系、Reeden 高亮规则（`.red`）、统一阅读素材库、高级标题与视觉资源包、网络与 DNS 分流等核心能力，均来自「阅读 Archive」时期的积累；LegadoH 在其代码基线上继续开发。
- **继承自上游 Legado**：自定义书源、替换净化、TTS 听书、RSS 订阅、Web 服务、WebDAV 备份等基础能力，源自 Legado（阅读 3.0）及其社区分支。
- **LegadoH 的增量**：品牌与发布独立（helyel233/legado-H）、包名与签名体系独立（`io.legado.app.LegadoH`），功能上持续移植社区分支的优秀改进——如完整移植了 [legadoC](https://github.com/CCSSNE/legadoC)（「阅读 Color」）的段评快照评论体系、缓存下载协调器、书架合集与首页自定义模块、正文插图与音频块播放，以及 [Legado_Max](https://github.com/Suml-1/Legado_Max) 的交互与工具特性——并按自身方向深化排版、外观体系与数据安全。

## 2.0 外观体系

2.0 把此前散落在主题、顶栏、底栏、玻璃与圆角入口的外观配置收拢为一个**外观中心**。外观不再是若干互不相干的开关，而是可新建、克隆、导入导出的**包**，按日/夜双槽挂载；切换日/夜时，材质氛围随槽位切换，几何参数保持全局一致。

| 包 | 包含 | 挂载 |
| --- | --- | --- |
| **美术资源包** | 12 项主题色板；主界面、顶栏、底栏、侧栏、书籍详情、面板、启动页 7 处壁纸；正文字体；顶栏/底栏图标；封面图集 | 日 / 夜双槽 |
| **参数设置包** | 材质氛围：档位、玻璃强度、模糊/着色/壳层/描边倍率、表面与弹窗不透明度、壁纸模糊；几何：全局圆角倍率、卡片阴影、空间密度与四区域覆写 | 氛围随日夜，几何全局单份 |
| **书架封面包** | 书架封面图集、套用模式（随机/顺序/混合/仅兜底）、日夜默认封面、分组封面 | 日 / 夜双槽 |

- **四档材质**：实底、透明、磨砂、液态玻璃。所有宿主共享同一套材质参数表；顶栏、底栏、书架卡片、弹窗四个区域可各自覆写档位，互不牵连。
- **Token 编译器**：颜色、圆角、透明度、阴影与空间密度按 Scope 级联派生，Compose 与 XML 两条渲染路径共用同一结果，避免同一观感在多个宿主各写一套。
- **包管理**：新建、克隆、导入、导出、重命名与删除。克隆采用写时复制，只存差异；导出自动物化为自包含 ZIP；同名拦截、内容哈希去重；删除仍被其他包引用的基底包前先物化，避免断链。
- **实时预览与草稿**：外观中心内嵌预览卡与圆角、不透明度、玻璃强度快捷微调；编辑器改动保留在草稿，未确认不写回已生效配置。
- **墨水屏独立轴**：墨水屏是独立的渲染降级开关，与「跟随系统 / 日间 / 夜间」正交，不再占用一个主题模式。
- **日夜不串档**：日/夜快照显式携带 `isNight`，捕获、套用与还原都写入对应槽位。

## 功能总览

| 方向 | 主要能力 |
| --- | --- |
| 阅读 | 书源搜索、发现与换源；本地 TXT/EPUB；目录与章节缓存；书签、阅读进度、阅读记录与目标；段评 |
| 书架 | 列表/网格、分组、标签与智能分组、批量管理、沉浸详情、书架封面包 |
| 首页与发现 | 模块化首页、榜单、书架合集、自定义发现模块与套件 |
| 排版 | 原生与 EPUB 双引擎、页面/加载模板、页眉页脚模板、横竖排、翻页动画、正文插图与音频块 |
| 高亮与规则 | `.red` 高亮、图案背景、替换净化、页面 HTML/CSS/JavaScript、正则试跑 |
| 外观 | 外观中心、美术/参数/封面包、四档材质、四区域覆写、日夜双槽、墨水屏、高级标题 Lottie、气泡包 |
| 听书与多媒体 | 系统与网络 TTS、多角色朗读与 AI 配乐、听书悬浮球、漫画与视频、播放缓存 |
| AI | AI 助手与工具调用、MCP 服务器、Tavily 联网检索、生图与图库；正文外发授权、密钥加密、限流与用量 |
| 自动化与数据 | 定时任务、缓存管理与导出、备份恢复（书源/书架/配置/资源包）、WebDAV、S3、规则回收站、删除撤销 |
| 网络与远程 | DNS/DoH、按功能分流、域名例外与测速、局域网 Web 服务、可选公网 Web 访问（Cloudflare Worker 中继） |

[功能细节（编写于「阅读 Archive」时期，原生/EPUB 排版对照仍适用）](docs/features.md) · [Web 与 Content Provider API](api.md)

## 其它亮点

- **书源「源所用 API」扫描**：书源编辑页一键列出规则中使用的全部 API（`java.xxx`、`cookie.xxx`、`ajax(` 等），按 API 分组显示使用位置与次数，支持搜索、跳转与复制。
- **书架标签管理**：管理页显示每个标签「已标记 N 本书」，支持标签栏显隐、全局重命名、批量移除，删除标签时自动清理成员书引用。
- **页眉页脚模板**：六个槽位支持占位符（`{书名}`、`{章节名}`、`{时间}`、`{电量}`、`{页码}`、`{总页数}`、`{进度}` 等）自由组合。
- **内置阅读模板与素材库**：Minecraft、明日香、诡秘之主、哆啦 A 梦、书刊、古籍竖排等页面模板开箱即用，可复制后编辑 HTML/CSS/JavaScript；图片与字体统一入库，高亮规则与页面模板共用同一份素材。
- **选区放大镜与快速滚动条**：长按选择文本时显示自绘放大镜，跨页选择更精准；书架与目录配备快速滚动条，长列表定位更快。
- **正则试跑与调试日志**：替换规则可先试运行正则；调试日志按应用、网络、规则、书源、RSS、Toast、校验、崩溃、阅读器分类，支持过滤、堆栈详情、导出与调试悬浮球。
- **规则回收站与删除撤销**：书源、订阅源、替换规则等 7 类规则删除前自动入站，默认保留 7 天；删除书源后可点提示条撤销，撤销时同步清理回收站副本。
- **空态引导与背景可读性**：空书架引导导入书源或添加本地书籍；自定义背景图下，全站文字自动加投影与半透明衬底，复杂壁纸上仍可阅读。
- **触觉反馈**：长按等操作统一经组件层触觉反馈入口触发，不同页面观感一致。

## 下载与安装

最新版本 **2.0.1**，前往 [GitHub Releases](https://github.com/helyel233/legado-H/releases) 获取安装包（tag 前缀 `H-`）；应用内「我的 → 关于 → 检查更新」指向同一页面。

- **包名**：正式版 `io.legado.app.LegadoH`，调试版 `io.legado.app.debug`；可与旧版「阅读 Archive」（`io.legado.app.Archive`）在设备上共存，数据互不影响。
- **安装包**：正式包按 arm64-v8a 构建（LegadoH 签名）；每个 Release 另附全 ABI 调试包（debug 签名，含 armeabi-v7a），方便非 arm64 设备试用。
- **迁移**：可从旧版 WebDAV 或本地备份恢复书源、书架与阅读配置；新版备份同时覆盖外观包与字体、封面等资源，恢复后按需对账，避免悬空引用。

## 本地构建

要求 **JDK 17** 与 **Android SDK 36**（`minSdk 21`，`targetSdk 36`）。

```bash
git clone https://github.com/helyel233/legado-H.git
cd legado-H
./gradlew :app:assembleAppDebug        # Debug APK，包名 io.legado.app.debug
./gradlew :app:testAppDebugUnitTest    # JVM 单元测试
```

- 构建变体为 `appDebug` / `appRelease`（flavor `app`）。
- 正式签名由维护者本地持有（`.legadoh/keystore.properties`，不入库）；未提供 `RELEASE_STORE_FILE` 等签名属性时，release 产物需自行配置签名后安装。

## 项目结构

| 路径 | 说明 |
| --- | --- |
| `app/` | Android 应用主体：阅读器、书架、外观中心、AI、备份、Web 服务与设置 |
| `modules/book/` | UMD 与 EPUB 解析库（`me.ag2s.umdlib` / `me.ag2s.epublib`） |
| `modules/rhino/` | 书源 JavaScript 规则使用的 Rhino 封装 |
| `baselineprofile/` | Baseline Profile 生成模块，用于冷启动与滚动路径预热 |
| `tools/` | 静态门禁、架构导读与阅读模板测试脚本 |
| `docs/` | 功能、设计、发版与开发文档 |

## 开发与门禁

新接手代码时，先跑 `python3 tools/architecture_guide.py`：它从源码实时生成导读（顶层枢纽类、策略类索引、术语映射、隐式契约锚点），不落盘、不会过期。

- [开发经验与规则](docs/development-playbook.md) · [改动安全网](docs/change-safety.md) · [发版手册](release-process.md)
- [UI 架构 v4 与双包体系](docs/ui-architecture-v4-plan.md) · [玻璃材质接入方案](docs/glass-appwide-adoption-plan.md) · [EPUB 直连引擎](docs/epub-direct-engine.md)
- [页面模板](docs/reader-templates.md) · [网络与 DNS](docs/doh-network.md) · [视觉资源包](docs/visual-resource-packages.md) · [段落规则与气泡包导入](docs/online-package-import.md) · [公网 Web 中继](docs/public-web-relay.md)

CI（[`verify.yml`](.github/workflows/verify.yml)）配置为在推送到 `main` 或提交 PR 时依次执行编译、JVM 单测与静态门禁：设置映射、资源英化、玻璃位运算、日夜键配对、UI 布局 token 基线；lint 目前只出报告，不阻塞构建。

## 免责声明

- 本项目仅供学习交流使用，请于下载后 24 小时内删除。
- 项目不内置、不存储、不传播任何书籍内容；所有网络内容均来自用户自行配置的第三方来源。
- 使用本项目产生的任何直接或间接后果由使用者自行承担，与开发者无关。请支持正版，尊重作者版权。

## 开源与致谢

感谢 [gedoor/legado](https://github.com/gedoor/legado)、[Luoyacheng/legado](https://github.com/Luoyacheng/legado) 及上游贡献者；感谢 [Rimchars/legado](https://github.com/Rimchars/legado)（「阅读 Archive」）——本项目直接继承自它的代码基线；感谢 [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) 社区分支的优秀改进。定时任务功能感谢明月的贡献与支持。

以下功能的移植来自社区分支，特此引用致谢：

- [CCSSNE/legadoC](https://github.com/CCSSNE/legadoC)（「阅读 Color」）：段评快照评论体系与离线评论、缓存下载协调器、书架合集与首页自定义模块、正文插图与音频块播放均整体移植自该项目，相关代码中保留了来源注释。
- [skxingyu/legado-sk](https://github.com/skxingyu/legado-sk)（「SK 版」）：书架变动自动备份、恢复时按备份覆盖书架、换源列表点击当前源行提示。
- [joestar817/legado_NG](https://github.com/joestar817/legado_NG)（「NG 版」）：每书独立阅读预设。
- [GEd520/legados](https://github.com/GEd520/legados)（「辞晨版」）：调试悬浮球、WebViewPool 即用即毁池隔离。

项目使用了 Rhino、Jsoup、OkHttp、Glide、Miuix、Paged.js、Lottie、Media3 等开源组件；各组件保留各自许可。应用内使用的组件说明见 [开源许可](app/src/main/assets/LICENSE.md)。

[更新日志](CHANGELOG.md) · [历史说明](docs/changelog/2026-07.md) · [上游历史日志](docs/changelog/upstream-2022.md)

## 许可证

本项目基于 [GPL-3.0](LICENSE) 许可证开源。

Copyright © 2026 LegadoH 贡献者
