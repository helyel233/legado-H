# AndroidLiquidGlassView — MIT 许可声明与来源

Legado-H 的发现页书源名行（1.25.10）复用了 `AndroidLiquidGlassView` 项目里的一个
**AGSL 着色器源文件**。本文件记录来源、许可全文，以及我们复制了哪个文件、改了什么。

## 来源

| 项 | 值 |
|---|---|
| 上游项目 | AndroidLiquidGlassView |
| 上游地址 | https://github.com/QmDeve/AndroidLiquidGlassView |
| 作者 | QmDeve（Contributors: Donny Yale, Ahmed Sbai） |
| 我们的依赖坐标 | `com.qmdeve.liquidglass:core:1.0.3` |
| 上游文件路径 | `res/raw/liquidglass_effect.agsl`（位于 `core-1.0.3.aar` 内） |
| 本仓库落点 | `app/src/main/res/raw/glass_row_liquid.agsl` |
| 许可 | MIT License, Copyright (c) 2025 QmDeve |

## 我们复制了什么、改了什么

- **只复制了着色器源码**（`main(float2 coord)` 及其全部辅助函数与 uniform 声明）。
  着色器数学与上游**逐字节一致**：没有任何 uniform 增删、没有常量调整、没有分支改写
  （可用 `diff --strip-trailing-cr` 对比本仓库文件与 AAR 内原文件核实）。
- 文件头在保留上游完整 MIT 声明与项目注释的前提下，追加了一段
  `Legado-H modification notice`，说明本仓库的用途与「未改动着色器数学」。
- **没有**复用上游的 Java/Kotlin 宿主（`com.qmdeve.liquidglass.LiquidGlass`、
  `com.qmdeve.liquidglass.widget.LiquidGlassView`）。原因见
  `docs/glass-inline-liquid-options.md` §1.4 与 §2 方案 E：库的宿主把 `content`
  这个 uniform shader 绑定在「每帧对采样源整树录制」的 `RenderNode` 上，是
  1.25.8 native 崩溃（RenderNode 重入录制被 hwui abort）的根因；而它的公开 API
  没有任何注入自定义纹理的入口。
- 我们自己的宿主实现见 `app/src/main/java/io/legado/app/ui/main/explore/RowGlassLiquidRenderer.kt`
  （`android.graphics.RuntimeShader` + `RenderEffect.createRuntimeShaderEffect`，
  采样源为仓库中已存在的降采样壁纸位图）。

> 底栏的 `StableLiquidGlassView` 仍按依赖坐标正常使用该库（MIT 许可下的二进制依赖），
> 与本文件的源码复制是两件独立的事。

---

## MIT License（上游许可全文）

```
MIT License

Copyright (c) 2025 QmDeve

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
