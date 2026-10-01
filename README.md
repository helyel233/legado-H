# Legado-H

[中文](README.md) · [English](English.md)

<p align="center"><img width="125" height="125" src="docs/archive_icon.svg" alt="Legado-H"></p>

Legado-H 是「阅读 Archive」的分支，在继承 [Legado](https://github.com/gedoor/legado) 与 Luoyacheng 分支的 EPUB 阅读引擎、页面/加载模板、主题体系等深度定制能力的同时，吸收 [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) 社区分支的优点改进，命名为 **Legado-H**。

应用不内置书籍内容。你可以自行添加书源，也可以导入本地 TXT、EPUB 书籍。

## 下载与更新

- [GitHub Releases](https://github.com/helyel233/legado-H/releases)：安装包与更新说明。

## 本分支特性

在 Archive 深度路线（EPUB 排版引擎、页面/加载模板、主题体系）的基础上，吸收 Legado_Max 的优点改进：

- **书源「源所用API」**：书源编辑页顶栏一键扫描书源规则中使用的 API（`java.xxx`、`cookie.xxx`、`ajax(` 等），按 API 分组显示使用位置与次数，支持搜索、点击跳转到对应字段、一键复制清单。
- **书架智能标签增强**：标签栏显示每个标签的命中书籍数；支持标签全局重命名（批量更新所有书籍的 `customTag` 并同步配置清单）；删除标签时自动清理分组成员身上的残留。
- **页眉页脚自定义模板**：页眉页脚六个槽位支持「自定义模板」，使用占位符（`{书名}` `{章节名}` `{时间}` `{电量}` `{电量百分比}` `{页码}` `{总页数}` `{进度}`）自由组合显示内容。
- **Lint 基线门禁**：合并统一 lint 配置并入库基线文件，存量问题不阻塞构建，仅拦截新增问题。
- **连续字重 100–900**：正文字重不再局限于常规/加粗两档，可在任意字重间细调（兼容旧配置）。
- **选区放大镜**：长按选择文本时显示放大镜，跨页选择更精准。

既有优势一览：

- 独立 EPUB 排版引擎与加载模板（默认「山茶花」），背景延伸至状态栏并适配日夜模式。
- 页面模板体系：分页模板固定画框滚动、字体配色页眉页脚统一管理、横排与竖排。
- 主题体系：Minecraft、明日香、诡秘之主、哆啦 A 梦四套内置阅读主题，高级标题与页眉页脚 Lottie 动效。

## 功能总览

| 方向 | 主要能力 |
| --- | --- |
| 阅读 | 书源与本地书籍、原生与 EPUB 排版、翻页动画、阅读样式、书签与进度 |
| 书架与详情 | 列表和网格、分组、智能标签、批量管理、沉浸详情、目录与定时更新 |
| 高亮与模板 | 本地 RED 规则、图案高亮、字体选择、页面 HTML/CSS/JavaScript、横排与竖排 |
| 素材与主题 | 共用图片字体库、日夜主题、背景、高级标题、页眉页脚与气泡 |
| 听书与多媒体 | 系统和网络 TTS、原文跟随、跨应用悬浮控件、漫画与视频入口 |
| AI | 可配置 AI 服务、书源搜索、书籍与章节读取、阅读记录查询及联网工具 |
| 自动化与数据 | 定时任务、缓存、备份恢复、WebDAV、对象存储与容器管理 |
| 网络 | DNS/DoH 选择、按功能分流、域名例外、服务配置与测速 |

[查看详细功能及模式差异](docs/features.md)

## 使用文档

- [页面模板：应用、分享与编写](docs/reader-templates.md)
- [网络与 DNS](docs/doh-network.md)
- [高级标题等视觉资源包](docs/visual-resource-packages.md)
- [段落规则和气泡包导入](docs/online-package-import.md)
- [Web 与 Content Provider API](api.md)
- [上游帮助文档](https://www.yuque.com/legado/wiki)

高亮规则使用本地 `.red` 文件导入。完整图片图案与复杂 CSS 效果使用 EPUB 渲染；普通正文的 EPUB 模式当前不启用段落规则与 `pclick`，原始图片 `click` 和替换净化继续支持。页面模板库提供独立备份。

## 开源与致谢

感谢 [gedoor/legado](https://github.com/gedoor/legado)、[Luoyacheng/legado](https://github.com/Luoyacheng/legado) 及上游贡献者；感谢 [Suml-1/Legado_Max](https://github.com/Suml-1/Legado_Max) 社区分支的优秀改进，Legado-H 从中吸收并持续完善。定时任务功能感谢明月的贡献与支持。

项目使用了 Rhino、Jsoup、OkHttp、Glide、Miuix、Paged.js 等开源组件；各组件保留各自许可。项目许可见 [LICENSE](LICENSE)，应用内使用的组件说明见 [开源许可](app/src/main/assets/LICENSE.md)。

[更新日志](CHANGELOG.md) · [历史说明](docs/changelog/2026-07.md) · [上游历史日志](docs/changelog/upstream-2022.md)
