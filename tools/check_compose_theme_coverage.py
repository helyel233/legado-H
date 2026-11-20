#!/usr/bin/env python3
"""Compose 主题契约覆盖率静态门禁（可重复执行，零依赖）。

检查所有包含 Compose `setContent { ... }` 的源文件，必须直接或通过共享收口点
接入 `LegadoComposeTheme`：
- LegadoComposeTheme
- AppDialogFrame
- AppManagementScaffold
- SettingSpecScreen
- ComposePreferenceScreen

用法：python3 tools/check_compose_theme_coverage.py [--root /path/to/repo]
退出码：0=100% 覆盖，1=发现未接入的 Compose setContent 文件。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SET_CONTENT_PAT = re.compile(r"\bsetContent\s*(?:\{|\()")
ENTRY_POINTS = (
    "LegadoComposeTheme",
    "AppDialogFrame",
    "AppManagementScaffold",
    "SettingSpecScreen",
    "ComposePreferenceScreen",
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Compose 主题契约覆盖率门禁")
    parser.add_argument("--root", default=".", help="仓库根目录")
    args = parser.parse_args()

    src_dir = Path(args.root) / "app" / "src" / "main" / "java"
    if not src_dir.is_dir():
        print(f"[FATAL] 找不到源码目录：{src_dir}", file=sys.stderr)
        return 1

    total = 0
    covered = 0
    uncovered: list[str] = []

    for kt in sorted(src_dir.rglob("*.kt")):
        text = kt.read_text(encoding="utf-8")
        if SET_CONTENT_PAT.search(text) and (
            "androidx.compose" in text or "ComposeView" in text or "LegadoComposeTheme" in text
        ):
            total += 1
            if any(ep in text for ep in ENTRY_POINTS):
                covered += 1
            else:
                uncovered.append(str(kt.relative_to(src_dir)))

    if uncovered:
        print(f"[FAIL] 发现 {len(uncovered)}/{total} 个 Compose setContent 文件未接入 LegadoComposeTheme 或共享收口点：")
        for item in uncovered:
            print(f"  - {item}")
        return 1

    print(f"[PASS] Compose 主题契约覆盖检查通过：{covered}/{total} (100%) 已接入。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
