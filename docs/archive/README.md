# 归档说明

本目录存放**已完成或已被取代**的规划/设计文档。内容原样保留（未改写），只做位置归档，
避免 `docs/` 顶层混入已失效的计划而误导后来的阅读。需要时 `git mv` 移回即可。

| 文件 | 归档理由 | 现行替代 |
| :--- | :--- | :--- |
| `2026-10-18-refactor-plan.md` | 2026-10-18 那次重构的动工前计划，末尾已追加各工作项的落地记录（W1 Web 服务口令、W2 PageView 拆分、阅读进度可测化等），计划本身不再驱动新工作 | 无（能力已并入主线，记录见 `CHANGELOG.md`） |
| `settings-optimization-plan.md` | 文件开头自述「早期版本（P 编号体系），**已停止更新**」 | `../settings-optimization-plan.html`（现行 R1–R50 结论） |
| `discovery-ux-prd.md` | 发现页体验优化 PRD，所描述的形态切换与异常降级已落地（`DiscoveryErrorType`、`ExploreGlassBackdrop` 均在代码中） | 无（已成事实，代码为准） |
| `discovery-ux-architecture.md` | 同上，配套系统设计与任务分解 | 同上 |
| `class-diagram.mermaid` | 上述发现页设计的类图 | 同上 |
| `sequence-diagram.mermaid` | 上述发现页设计的时序图（`ExploreFragment → ExploreGlassBackdrop`） | 同上 |

**未归档但名字容易被误认为过期的两个文件**：

- `../system_design.md` —— 名字泛，但内容是「毛玻璃效果设置项重构」的系统设计，
  `CHANGELOG.md` 与 `app/src/main/assets/updateLog.md` 都把它作为该能力的架构记录引用，故留在原处。
- `../glass-appwide-adoption-plan.md` —— 任务表里仍有「未接入（设计见 §4）」的行，属在办计划。
