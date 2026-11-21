#!/usr/bin/env python3
"""UI 空间契约巡检脚本（report-only 默认不阻断，支持 --strict）。

检查两个 M1-M4 空间规范回退风险：
1. 交互控件/列表行误用固定 `.height(N.dp)`（大字号 `fontScale > 1.3x` 下易裁切文字），
   推荐改用 `.heightIn(min = ...)` 或 `.defaultMinSize(minHeight = ...)`；
2. 设置/我的模块中残留的 `Spacer(...height(8.dp))`（需人工甄别是合法项间距还是旧版主副标题间距）。

用法：
  python3 tools/check_ui_layout_tokens.py [--root /path/to/repo] [--strict]
退出码：
  默认 report-only：目录存在时恒为 0；
  --strict：发现未豁免项时返回 1。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

FIXED_HEIGHT_DP = re.compile(r"\.height\(\s*(\d+(?:\.\d+)?)\.dp\s*\)")
SPACER_8DP = re.compile(r"Spacer\s*\([^)]*\.height\(\s*8\.dp\s*\)")

# 小于等于 28dp 通常是分割线/进度条/小图标；较大尺寸或明确非文本容器（Spacer/图标/滑杆/封面预览）放行
EXEMPT_LINE_KEYWORDS = (
    "Spacer(",
    "Divider",
    "HorizontalDivider",
    "DividerDefaults",
    "trackHeight",
    "thumb",
    "Icon(",
    "Image(",
    "shimmer",
    "ProgressIndicator",
    "ui-token-ignore",
)

# 仅针对典型交互控件/行/输入槽高度区间做提醒（dp）
SUSPECT_CONTROL_HEIGHT_MIN = 30.0
SUSPECT_CONTROL_HEIGHT_MAX = 80.0


def scan_file(path: Path, rel: Path) -> list[tuple[int, str, str]]:
    findings: list[tuple[int, str, str]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (UnicodeDecodeError, OSError):
        return findings

    rel_posix = rel.as_posix()
    in_config_or_my = (
        "/ui/config/" in rel_posix or "/ui/main/my/" in rel_posix
    )

    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        window = "\n".join(lines[max(0, idx - 3) : min(len(lines), idx + 2)])
        if any(kw in window for kw in EXEMPT_LINE_KEYWORDS):
            if in_config_or_my and SPACER_8DP.search(stripped) and "ui-token-ignore" not in window:
                findings.append(
                    (
                        idx,
                        "SPACER_8DP",
                        f"{stripped}  (核实是否为主副标题间距；若是请用 AppLayoutTokens.TitleToSummaryGap)",
                    )
                )
            continue

        for m in FIXED_HEIGHT_DP.finditer(stripped):
            dp_val = float(m.group(1))
            if SUSPECT_CONTROL_HEIGHT_MIN <= dp_val <= SUSPECT_CONTROL_HEIGHT_MAX:
                findings.append(
                    (
                        idx,
                        "FIXED_CONTROL_HEIGHT",
                        f"{stripped}  ({m.group(0)} 在大字号下有裁切风险，建议 heightIn/defaultMinSize)",
                    )
                )

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="UI 空间契约巡检（report-only）")
    parser.add_argument("--root", default=".", help="仓库根目录")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="严格模式：存在发现项时以退出码 1 结束",
    )
    args = parser.parse_args()

    root = Path(args.root)
    ui_dir = root / "app" / "src" / "main" / "java" / "io" / "legado" / "app" / "ui"
    if not ui_dir.is_dir():
        print(f"[FATAL] 找不到 UI 源码目录：{ui_dir}", file=sys.stderr)
        return 1

    all_findings: list[tuple[Path, int, str, str]] = []
    scanned = 0
    for kt in sorted(ui_dir.rglob("*.kt")):
        scanned += 1
        rel = kt.relative_to(root)
        for line_no, kind, msg in scan_file(kt, rel):
            all_findings.append((rel, line_no, kind, msg))

    for rel, line_no, kind, msg in all_findings:
        print(f"[REPORT][{kind}] {rel}:{line_no} -> {msg}")

    mode = "STRICT" if args.strict else "REPORT-ONLY"
    print(
        f"[{mode}] UI 空间契约巡检完成：扫描 {scanned} 个文件，发现 {len(all_findings)} 条待核查项。"
    )
    if args.strict and all_findings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
