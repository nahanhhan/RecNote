"""检查入口：顺序执行各检查，汇总输出，统一退出码语义（0=全部通过，1=存在失败）。"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Callable

from . import json_assets, kotlin_comments, log_redaction, model_sources, version_consistency

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# 本文件位于 <repo>/scripts/tests/checks/__main__.py
REPO_ROOT = Path(__file__).resolve().parents[3]

CheckFn = Callable[[Path], list[str]]

CHECKS: list[tuple[str, CheckFn]] = [
    ("版本一致性", version_consistency.run),
    ("JSON 资产", json_assets.run),
    ("下载源", model_sources.run),
    ("Kotlin 注释配对", kotlin_comments.run),
    ("日志脱敏规则", log_redaction.run),
]


def main() -> int:
    failures = 0
    for name, fn in CHECKS:
        errors = fn(REPO_ROOT)
        if errors:
            failures += 1
            print(f"[FAIL] {name}")
            for error in errors:
                print(f"  - {error}")
        else:
            print(f"[PASS] {name}")
    total = len(CHECKS)
    if failures:
        print(f"\n{failures}/{total} 项检查失败")
        return 1
    print(f"\n全部 {total} 项检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())