#!/usr/bin/env python3
"""Day/Night PreferKey pair consistency check (zero dependencies).

Verifies that every Day/Night pair defined in PreferKey.kt (ending with N / Night / Dark,
or infix N before uppercase, or Day/Night package pointer pairs) is registered in
`NightKeyPairs.kt` and that `ThemeRuntimeKeys`, `BackupConfig`, and
`PARAM_LIVE_SNAPSHOT_KEYS` derive from `NightKeyPairs`.

Usage: python3 tools/check_night_key_pairs.py [--root /path/to/repo]
Exit code: 0 = pass, 1 = missing/broken pairs found.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

BASE = "app/src/main/java/io/legado/app"
PREFER_PATH = f"{BASE}/constant/PreferKey.kt"
REGISTRY_PATH = f"{BASE}/lib/theme/NightKeyPairs.kt"
CONSUMERS = (
    f"{BASE}/lib/theme/ThemeRuntimeKeys.kt",
    f"{BASE}/help/storage/BackupConfig.kt",
    f"{BASE}/help/config/pkg/UnifiedPackageRepository.kt",
)


def main() -> int:
    ap = argparse.ArgumentParser(description="日夜双键唯一注册表校验")
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    args = ap.parse_args()
    root = Path(args.root)

    prefer_src = (root / PREFER_PATH).read_text(encoding="utf-8")
    registry_src = (root / REGISTRY_PATH).read_text(encoding="utf-8")

    consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]+)"', prefer_src))
    registered = set(
        re.findall(r"PreferKey\.(\w+)\s+to\s+PreferKey\.(\w+)", registry_src)
    )
    registered_flat = {k for pair in registered for k in pair}

    problems: list[str] = []

    for day, night in sorted(registered):
        if day not in consts:
            problems.append(f"NightKeyPairs 引用了不存在的 Day 键: PreferKey.{day}")
        if night not in consts:
            problems.append(f"NightKeyPairs 引用了不存在的 Night 键: PreferKey.{night}")

    # Detect all PreferKey names that look like Night counterparts
    night_pattern = re.compile(r"(N|Night|Dark)$")
    infix_n_pattern = re.compile(r"^([a-z]+)N([A-Z]\w*)$")
    for name in sorted(consts):
        is_night_like = (
            bool(night_pattern.search(name))
            or bool(infix_n_pattern.match(name))
            or "Night" in name
        )
        if not is_night_like:
            continue
        # Find its Day counterpart if one exists in PreferKey
        candidates = []
        if name.endswith("Night"):
            candidates.extend([name[:-5], name[:-5] + "Day"])
            candidates.append(name.replace("Night", "Day"))
        if name.endswith("Dark"):
            candidates.append(name[:-4])
        if name.endswith("N"):
            candidates.append(name[:-1])
        m_infix = infix_n_pattern.match(name)
        if m_infix:
            candidates.append(m_infix.group(1) + m_infix.group(2))
        if "bgImageN" in name:
            candidates.append(name.replace("bgImageN", "bgImage"))
        has_day_partner = any(c != name and c in consts for c in candidates)
        if has_day_partner and name not in registered_flat:
            matched_days = [c for c in candidates if c != name and c in consts]
            problems.append(
                f"PreferKey.{name} (对应 {matched_days[0]}) 未登记进 NightKeyPairs.kt"
            )

    for rel in CONSUMERS:
        src = (root / rel).read_text(encoding="utf-8")
        if "NightKeyPairs." not in src:
            problems.append(f"{rel} 未从 NightKeyPairs 派生")

    if problems:
        for p in problems:
            print(f"[FAIL] {p}")
        return 1

    print(
        f"[PASS] 日夜双键注册表校验通过：NightKeyPairs 已登记 {len(registered)} 对，"
        f"PreferKey 全量扫描零遗漏，3 处消费方均派生自 NightKeyPairs。"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
