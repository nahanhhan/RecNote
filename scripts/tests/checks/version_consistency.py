"""版本一致性检查：app/build.gradle.kts 的 versionName 与 README 声明版本。"""

from __future__ import annotations

import re
from pathlib import Path

GRADLE_FILE = Path("app/build.gradle.kts")
README_FILE = Path("README.md")

_VERSION_NAME = re.compile(r'versionName\s*=\s*"([^"]+)"')
_README_VERSION = re.compile(r"当前版本为\s*\*\*([^*]+)\*\*")


def run(root: Path) -> list[str]:
    errors: list[str] = []
    gradle_path = root / GRADLE_FILE
    readme_path = root / README_FILE

    gradle_match = None
    if gradle_path.is_file():
        gradle_match = _VERSION_NAME.search(gradle_path.read_text(encoding="utf-8"))
        if not gradle_match:
            errors.append(f"{GRADLE_FILE.as_posix()}: 未找到 versionName 声明")
    else:
        errors.append(f"{GRADLE_FILE.as_posix()}: 文件不存在")

    readme_match = None
    if readme_path.is_file():
        readme_match = _README_VERSION.search(readme_path.read_text(encoding="utf-8"))
        if not readme_match:
            errors.append(f"{README_FILE.as_posix()}: 未找到版本声明（当前版本为 **x.y.z**）")
    else:
        errors.append(f"{README_FILE.as_posix()}: 文件不存在")

    if gradle_match and readme_match and gradle_match.group(1) != readme_match.group(1):
        errors.append(
            f"版本不一致: {GRADLE_FILE.as_posix()} versionName=\"{gradle_match.group(1)}\" "
            f"vs {README_FILE.as_posix()} 声明 \"{readme_match.group(1)}\""
        )
    return errors