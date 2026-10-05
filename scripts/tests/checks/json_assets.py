"""JSON 资产检查：工具定义与文档示例可解析且相互一致。"""

from __future__ import annotations

import json
from pathlib import Path

TOOL_DEFINITION_FILES = (
    Path("app/src/main/assets/tool-definition.json"),
    Path("docs/openai-tool-calling/tool-definition.json"),
)
EXAMPLES_DIR = Path("docs/openai-tool-calling/examples")


def _load(path: Path, rel: str, errors: list[str]):
    if not path.is_file():
        errors.append(f"{rel}: 文件不存在")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        errors.append(f"{rel}: JSON 解析失败 ({exc})")
        return None


def run(root: Path) -> list[str]:
    errors: list[str] = []

    parsed = {}
    for rel in TOOL_DEFINITION_FILES:
        data = _load(root / rel, rel.as_posix(), errors)
        if data is not None:
            parsed[rel.as_posix()] = data

    examples_dir = root / EXAMPLES_DIR
    if not examples_dir.is_dir():
        errors.append(f"{EXAMPLES_DIR.as_posix()}: 目录不存在")
    else:
        example_files = sorted(examples_dir.glob("*.json"))
        if not example_files:
            errors.append(f"{EXAMPLES_DIR.as_posix()}: 未找到示例 JSON")
        for example in example_files:
            _load(example, example.relative_to(root).as_posix(), errors)

    if len(parsed) == len(TOOL_DEFINITION_FILES):
        (name_a, data_a), (name_b, data_b) = parsed.items()
        if data_a != data_b:
            errors.append(f"两处工具定义不一致: {name_a} vs {name_b}")
    return errors