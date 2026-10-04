# LegadoH

[中文](README.md) · [English](English.md)

<p align="center"><img width="128" height="128" src="docs/legadoh_icon.svg" alt="LegadoH"></p>

<p align="center"><b>LegadoH</b> —— 一款可自定义来源与界面的 Android 阅读器，继承自「阅读 Archive」，血统上溯至 <a href="https://github.com/gedoor/legado">Legado（阅读 3.0）</a>。</p>

---

## 项目渊源

LegadoH 并非从零开始的项目，它完整继承了一条持续演进的分支血统：

```
gedoor/legado（阅读 3.0）
  └─ Luoyacheng/legado
       └─ Rimchars/legado（「阅读 Archive」）
            └─ helyel233/legado-H（LegadoH，本项目）
```

- **继承自阅读 Archive**：独立 EPUB 排版引擎、页面/加载模板体系、Reeden 高亮规则（`.red`）、统一阅读素材库、高级标题与视觉资源包、网络与 DNS 分流等核心能力，均来自「阅读 Archive」时期的积累，LegadoH 在其代码基线上继续开发。
- **继承自上游 Legado**：自定义书源、替换净化、TTS 听书、RSS 订阅、Web 服务、WebDAV 备份等基础能力，源自 Legado（阅读 3.0）及其社区分支。
- **LegadoH 的增量**：品牌与发布独立（helyel233/legado-H）、包名与签名体系独立（`io.legado.app.LegadoH`）、功能上持续移植社区分支的优秀改进——如完整移植了 [legadoC](https://github.com/CCSSNE/legadoC)（「阅读Color」）的段评快照评论体系、缓存下载协调器、书架合集与首页自定义模块、正文插图与音频块播放，以及 [Legado_Max](https://github.com/Suml-1/Legado_Max) 的交互与工具特性——并按自身方向深化排版、主题与数据安全。

## 简介

LegadoH 不内置任何书籍或书源内容，你可以在应用内自行添加网络书源，也可以导入本地 TXT、EPUB 书籍。项目在完整继承上述能力的基础上，围绕**排版质量、主题外观与阅读工具**做了大量深度定制：

- **独立 EPUB 排版引擎**：支持图片图案高亮与复杂 CSS 效果，默认「山茶花」加载模板，背景延伸至状态栏并适配日夜模式。
- **页面模板体系**：分页模板固定画框滚动、字体配色页眉页脚统一管理、横排与竖排自由切换。
- **主题体系**：Minecraft、明日香、诡秘之主、哆啦 A 梦等内置阅读主题，高级标题与页眉页脚 Lottie 动效，顶栏/底栏/弹窗不透明度可独立调节。
- **AI 辅助阅读**：可配置 AI 服务，支持书源搜索、书籍与章节内容读取、阅读记录查询及联网工具。
- **界面现代化**：Compose 书架与发现页、自适应图标与主题图标单色层、毛玻璃顶栏与底栏。

## 下载

前往 [GitHub Releases](https://github.com/helyel233/legado-H/releases) 获取最新安装包（tag 前缀 `legadoh-`），应用内「我的 → 关于 → 检查更新」同样指向本仓库 Releases。

- 包名：`io.legado.app.LegadoH`，可与旧版「阅读 Archive」（`io.legado.app.Archive`）在设备上**共存**，互不影响。
- 两版数据完全独立；可从旧版的 WebDAV/本地备份一键恢复书源、书架与阅读配置。
- 每次发布同时提供正式版（arm64-v8a，LegadoH 签名）与调试版（全 ABI，debug 签名）两个安装包。

## 功能总览

| 方向 | 主要能力 |
| --- | --- |
| 阅读 | 书源与本地书籍、原生与 EPUB 排版、翻页动画、阅读样式、书签与进度 |
| 书架与详情 | 列表和网格、分组、智能标签（命中计数/全局重命名）、批量管理、沉浸详情 |
| 排版与模板 | 独立 EPUB 引擎、页面/加载模板、页眉页脚自定义模板（占位符组合）、横竖排 |
| 高亮与规则 | 本地 `.red` 规则、图案高亮、正则净化、页面 HTML/CSS/JavaScript |
| 素材与主题 | 共用图片字体库、日夜主题、背景、高级标题 Lottie、气泡包、连续字重 100–900 |
| 听书与多媒体 | 系统和网络 TTS、原文跟随、跨应用悬浮控件、漫画与视频入口、视频边播边缓存 |
| AI | 可配置 AI 服务、书源搜索、书籍与章节读取、阅读记录查询及联网工具 |
| 自动化与数据 | 定时任务、缓存、备份恢复（含书籍文件与素材）、规则回收站、缓存导出、WebDAV、对象存储（S3） |
| 网络 | DNS/DoH 选择、按功能分流、域名例外、服务配置与测速 |

[查看详细功能及模式差异](docs/features.md)

## 定制亮点

- **书源「源所用API」扫描**：书源编辑页一键列出规则中使用的全部 API（`java.xxx`、`cookie.xxx`、`ajax(` 等），按 API 分组显示使用位置与次数，支持搜索、跳转与复制。
- **书架智能标签增强**：标签栏显示每个标签的命中书籍数；支持全局重命名与删除时自动清理残留。
- **页眉页脚自定义模板**：六个槽位支持占位符（`{书名}` `{章节名}` `{时间}` `{电量}` `{页码}` `{总页数}` `{进度}` 等）自由组合。
- **选区放大镜与快速滚动条**：长按选择文本时显示自绘放大镜，跨页选择更精准；阅读页配备 Max 风格浮动拖柄快速滚动条。
- **正则测试与调试日志**：替换规则内置正则试运行界面；调试日志中心汇集应用日志、Toast 与崩溃记录，便于排查书源问题。
- **规则回收站**：书源、订阅源、替换规则等 7 类规则删除前自动入站，默认保留 7 天，可随时恢复。
- **Lint 基线门禁**：统一 lint 配置并入库基线文件，存量问题不阻塞构建，仅拦截新增问题。

## 使用文档

- [页面模板：应用、分享与编写](docs/reader-templates.md)
- [网络与 DNS](docs/doh-network.md)
- [高级标题等视觉资源包](docs/visual-resource-packages.md)
- [段落规则和气泡包导入](docs/online-package-import.md)
- [Web 与 Content Provider API](api.md)
- [上游帮助文档](https://www.yuque.com/legado/wiki)

## 本地构建

```bash
git clone https://github.com/helyel233/legado-H.git
cd legado-H
./gradlew :app:assembleAppDebug        # Debug 构建
./gradlew :app:testAppDebugUnitTest    # 单元测试
```

要求 JDK 17 及 Android SDK 36。Release 签名密钥由维护者本地持有（`.legadoh/`，不入库），未配置签名时 Release 变体回退 CI 调试证书。

## 免责声明

- 本项目仅供学习交流使用，请于下载后 24 小时内删除。
- 项目不内置、不存储、不传播任何书籍内容；所有网络内容均来自用户自行配置的第三方来源。
- 使用本项目产生的任何直接或间接后果由使用者自行承担，与开发者无关。请支持正版，尊重作者版权。

## 开源与致谢

感谢 [gedoor/legado](https://github.com/gedoor/legado)、[Luoyacheng/legado](https://github.com/Luoyacheng/legado) 及上游贡献者；感谢 [Rimchars/legado](https://github.com/Rimchars/legado)（「阅读 Archive」）——本项目直接继承自它的代码基线；感谢 [CCSSNE/legadoC](https://github.com/CCSSNE/legadoC)（「阅读Color」）——本项目的段评快照评论体系与离线评论、缓存下载协调器、书架合集与首页自定义模块、正文插图与音频块播放均整体移植自该项目，相关代码中保留了来源注释；感谢 [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) 社区分支的优秀改进。定时任务功能感谢明月的贡献与支持。

项目使用了 Rhino、Jsoup、OkHttp、Glide、Miuix、Paged.js 等开源组件；各组件保留各自许可。应用内使用的组件说明见 [开源许可](app/src/main/assets/LICENSE.md)。

[更新日志](CHANGELOG.md) · [历史说明](docs/changelog/2026-07.md) · [上游历史日志](docs/changelog/upstream-2022.md)

## 许可证

本项目基于 [GPL-3.0](LICENSE) 许可证开源。

Copyright © 2026 LegadoH 贡献者
