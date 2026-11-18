#!/usr/bin/env python3
"""玻璃/位运算治理门禁（可重复执行，零依赖）。

拦一个曾让全 App 软件玻璃静默失效一个月的位运算反模式：

    pix[yi] = (0xff000000.toInt() shl 24) or ...

`0xff000000.toInt()` 已经是 -16777216（0xFF000000，即已带 alpha 的完整 ARGB），
再 `shl 24` 会把仅存的低 8 位（此时全是 0）留在原处 → 结果恒为 0 → 整张模糊位图 alpha=0，
画到 Canvas / BitmapShader 上等于什么都没画。

根因案例（2026-11-12 复盘，见 output/bug-review-2026-11-17.md 根因③）：
阅读页齿轮面板「半透明但不糊」+ 右上角菜单「选项无背景框」两个缺陷同源于这一行。

判别规则（零误报，已对照现有全部 19 处 `shl 24` 逐一验证）：
对每个 `shl 24`，取其左侧最近的十六进制字面量 0x...，若该字面量数值 >= 0x1000000
（即本身已占用 ARGB 的高 8 位 alpha），判定为反模式。

合法形态（不拦）：
    (alpha shl 24)                     // alpha 是变量
    (0xFF shl 24) or (red shl 16)      // 0xFF 本身不含 alpha，移位后才落到 alpha 位
    (bytes[i].toInt().and(0xff) shl 24)// 0xff 同理
    (nextAlpha shl 24) or (x and 0x00ffffff) // 0x00ffffff < 0x1000000

用法：python3 tools/check_glass_governance.py [--root /path/to/repo]
退出码：0=通过，1=发现反模式。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

HEX = re.compile(r"0[xX][0-9a-fA-F]+")
SHL24 = re.compile(r"shl\s*24")
# 值 >= 0x1000000 说明字面量本身已携带 ARGB 高 8 位 alpha
ALPHA_BIT = 0x1000000


def scan_file(path: Path) -> list[tuple[int, str, str]]:
    """返回 [(行号, 行内容, 命中的十六进制字面量), ...]"""
    hits = []
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return hits
    for line_no, line in enumerate(text.splitlines(), start=1):
        for m in SHL24.finditer(line):
            prefix = line[: m.start()]
            nearest = None
            for hm in HEX.finditer(prefix):
                nearest = hm
            if nearest is None:
                continue
            value = int(nearest.group(0), 16)
            if value >= ALPHA_BIT:
                hits.append((line_no, line.strip(), nearest.group(0)))
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description="玻璃/位运算治理门禁")
    parser.add_argument("--root", default=".", help="仓库根目录")
    args = parser.parse_args()

    root = Path(args.root)
    src_dir = root / "app" / "src" / "main" / "java"
    if not src_dir.is_dir():
        print(f"[FATAL] 找不到源码目录：{src_dir}", file=sys.stderr)
        return 1

    total_hits = 0
    for kt in sorted(src_dir.rglob("*.kt")):
        hits = scan_file(kt)
        for line_no, content, token in hits:
            total_hits += 1
            rel = kt.relative_to(root)
            print(f"[FAIL] {rel}:{line_no}")
            print(f"       反模式：十六进制字面量 {token} (值 >= 0x1000000) 再 shl 24，结果 alpha 恒 0")
            print(f"       {content}")
            print(f"       正确写法：直接写 {token}（去掉 shl 24），或改用变量 alpha shl 24")
            print()

    if total_hits:
        print(f"[FAIL] 发现 {total_hits} 处位运算反模式。见 docs/change-safety.md R3。")
        return 1
    print("[PASS] 位运算治理检查通过：无『带 alpha 的常量再 shl 24』反模式。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
