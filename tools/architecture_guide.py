#!/usr/bin/env python3
"""按需生成新人导读——从源码实时提取，不落盘、不维护、因此不会腐烂。

设计动机（为什么不是一份静态 onboarding.md）：
  本仓库命名约定不统一（多社区分支移植），任何「按文件名/类名推导契约」的
  静态校验都会大面积误报：实测文档 .kt 引用 8 个候选多为规划文件、跨包重名
  7 个全合理、文件名≠类名 76 个多为多类型文件、孤儿测试 82/313 误报。
  静态导读会重演 docs/ 的腐烂（时间倒挂 + 清单全未勾选却称完成）。
  因此改为：脚本每次从当前源码实时读取，输出即真相，无需同步、无需 CI 门禁。

产出四块：
  1. 顶层枢纽类         —— 新人最先该认识的全局状态/配置/引擎入口
  2. 策略类索引         —— *Policy/*Guard/*Registry/*Gate，按包分组，含方法签名
  3. 术语映射           —— 同概念多叫法（引导读者按域区分，不强制改名）
  4. 隐式契约注释锚点   —— 「不能删/看似bug/兼容老数据」等，给出文件:行号

用法：python3 tools/architecture_guide.py [--root /path/to/repo] [--section all|hub|policy|glossary|contracts]
只读，零依赖，不改任何文件。
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

BASE = "app/src/main/java/io/legado/app"

# 顶层枢纽类：路径 + 一句话为什么新人必须先读（人工挑选的「入口」，不含会腐烂的行号）
HUB: list[tuple[str, str]] = [
    ("App.kt", "Application：全局初始化顺序、协程默认作用域、TLS Provider 安装的时序契约"),
    ("help/config/AppConfig.kt", "进程级万能配置单例（3260 行）：读写语义混合，onSharedPreferenceChanged 巨型 when"),
    ("model/ReadBook.kt", "全局阅读会话：setter 有副作用，字段跨线程，@Volatile 契约写在注释里"),
    ("ui/book/read/ReadBookActivity.kt", "阅读界面总控（7154 行 / 17 个回调接口）：直连 EPUB 状态的两套序号在此汇合"),
    ("ui/book/read/epub/EpubDirectWebLayer.kt", "EPUB 直连渲染层（8913 行）：翻页/JS 桥接/截图/预加载的隐式状态机"),
    ("model/analyzeRule/AnalyzeRule.kt", "书源规则 DSL 解析 + evalJS 注入 java 对象：局部读不懂，需全局上下文"),
    ("model/analyzeRule/RuleAnalyzer.kt", "手写递归下降规则解析器：游标状态机，改一处牵全身"),
    ("model/SharedJsScope.kt", "JS 共享库作用域：MD5 key + LRU + 弱引用 + 磁盘缓存的生命周期"),
    ("data/DatabaseMigrations.kt", "59 个版本迁移：版本断层 + 幂等 repair 辅助函数"),
    ("constant/PreferKey.kt", "偏好键字符串协议：拼写错误被固化为兼容契约"),
    ("help/coroutine/Coroutine.kt", "进程级 defaultScope 与 viewModelScope 的选用分界"),
    ("domain/gateway/HomepageModulesGateway.kt", "单实现 Gateway + 冗余 DTO 映射：分层边界示例（含争议）"),
]

# 术语映射：同概念多叫法，说明语境差异而非强推统一（统一会破坏数据协议）
GLOSSARY: list[tuple[str, str]] = [
    ("书源", "source / origin / bookSource / BookSource / sy",
     "origin 是 Book 实体字段与 Web/备份协议字段，跨版本兼容不能改；bookSource 多为实体与 UI 概念。按域区分，勿全局替换。"),
    ("章节 / 目录", "chapter / toc / volume / chapterList",
     "chapter 指内容，toc 指目录；volume 见于本地书库模型。"),
    ("配置", "config / settings / prefs / preference / option",
     "config 占多数；prefs 偏 SharedPreferences 存储层；option 多为局部枚举。"),
    ("任务 / 协程", "scope / Job / coroutine / task",
     "scope 指协程作用域；task 指业务任务（AutoTask）；两者混用需按上下文判断。"),
]

CONTRACT_RE = re.compile(
    r"(不能删|勿删|不要删|务必保留|看似 ?bug|不是死代码|历史原因|兼容老数据|"
    r"不要改|不能改|不可改|逐字节|等价|语义不变|行为不变)"
)

CLASS_RE = re.compile(
    r"^\s*(?:(?:internal|private|public|open|sealed|data|enum|abstract|inner)\s+)*"
    r"(class|object|interface)\s+(\w+)",
    re.M,
)
PKG_RE = re.compile(r"^package\s+([\w.]+)", re.M)
FUN_RE = re.compile(r"^\s*(?:private\s+|internal\s+|protected\s+|public\s+|override\s+)*"
                    r"(?:suspend\s+)?fun\s+(\w+)\s*\(", re.M)

STRATEGY_SUFFIX = ("Policy", "Guard", "Registry", "Gate")


def read_sources(root: Path) -> list[tuple[Path, str]]:
    base = root / BASE
    if not base.is_dir():
        raise SystemExit(f"[FATAL] 找不到源码根目录：{base}")
    out = []
    for p in base.rglob("*.kt"):
        if "build" in p.parts:
            continue
        out.append((p, p.read_text(encoding="utf-8", errors="ignore")))
    if len(out) < 100:
        raise SystemExit(f"[FATAL] 源码文件数异常偏少（{len(out)}），拒绝给出误导性导读")
    return out


def rel(root: Path, p: Path) -> str:
    return str(p.relative_to(root))


def section_hub(root: Path) -> list[str]:
    lines = ["=" * 72, "1. 顶层枢纽类（新人最先该认识）", "=" * 72]
    for path, why in HUB:
        full = root / BASE / path
        flag = "OK" if full.is_file() else "MISSING"
        lines.append(f"  [{flag:7s}] {BASE}/{path}")
        lines.append(f"           {why}")
    return lines


def section_policy(root: Path, sources: list[tuple[Path, str]]) -> list[str]:
    groups: dict[str, list[tuple[str, list[str]]]] = defaultdict(list)
    for p, txt in sources:
        if not p.stem.endswith(STRATEGY_SUFFIX):
            continue
        m = PKG_RE.search(txt)
        pkg = m.group(1).replace("io.legado.app.", "") if m else "?"
        classes = [name for _, name in CLASS_RE.findall(txt)]
        funcs = FUN_RE.findall(txt)
        shown = ", ".join(funcs[:6]) + (" ..." if len(funcs) > 6 else "")
        groups[pkg].append((p.stem, shown if shown else "(无顶层 fun)"))
    lines = ["", "=" * 72,
             f"2. 策略类索引（*Policy/*Guard/*Registry/*Gate，共 {sum(len(v) for v in groups.values())} 个）",
             "   这些是各巨型文件里被抽出的纯逻辑判定——先找这里，别直接读 8000 行主文件",
             "=" * 72]
    for pkg in sorted(groups):
        lines.append(f"  [{pkg}]")
        for name, funcs in sorted(groups[pkg]):
            lines.append(f"    - {name}: {funcs}")
    return lines


def section_glossary() -> list[str]:
    lines = ["", "=" * 72, "3. 术语映射（同概念多叫法——按域区分，勿全局替换）", "=" * 72]
    for term, names, note in GLOSSARY:
        lines.append(f"  {term}: {names}")
        lines.append(f"      {note}")
    return lines


def section_contracts(root: Path, sources: list[tuple[Path, str]]) -> list[str]:
    hits = 0
    lines = ["", "=" * 72,
             "4. 隐式契约注释锚点（改这里前先读注释，往往是「看似 bug 实为行为」）",
             "=" * 72]
    for p, txt in sources:
        for no, line in enumerate(txt.splitlines(), 1):
            s = line.strip()
            if not (s.startswith("*") or s.startswith("//")):
                continue
            if CONTRACT_RE.search(s):
                hits += 1
                snippet = s.lstrip("*/ ").strip()
                if len(snippet) > 96:
                    snippet = snippet[:93] + "..."
                lines.append(f"  {rel(root, p)}:{no}  {snippet}")
    lines.insert(3, f"  （共 {hits} 处，仅列主源码注释；完整语义请打开原文件）")
    return lines


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--section", choices=["all", "hub", "policy", "glossary", "contracts"], default="all")
    args = ap.parse_args()
    root = Path(args.root).resolve()

    sources = read_sources(root)
    print("LegadoH 新人导读（从源码实时生成 · 不落盘 · 不会腐烂）")
    print(f"扫描主源码 {len(sources)} 个 .kt 文件")
    if args.section in ("all", "hub"):
        print("\n".join(section_hub(root)))
    if args.section in ("all", "policy"):
        print("\n".join(section_policy(root, sources)))
    if args.section in ("all", "glossary"):
        print("\n".join(section_glossary()))
    if args.section in ("all", "contracts"):
        print("\n".join(section_contracts(root, sources)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
