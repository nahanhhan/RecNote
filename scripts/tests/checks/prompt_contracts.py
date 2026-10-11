"""提示词边界检查：避免工具说明重新锁定写作风格，或调试日志绕过系统消息省略。"""
from __future__ import annotations

import json
from pathlib import Path


def run(root: Path) -> list[str]:
    errors: list[str] = []
    definitions = (
        Path("app/src/main/assets/tool-definition.json"),
        Path("docs/openai-tool-calling/tool-definition.json"),
    )
    for rel in definitions:
        try:
            tool = json.loads((root / rel).read_text(encoding="utf-8"))["function"]
            if not isinstance(tool, dict) or "name" not in tool:
                raise ValueError("missing function definition")
        except (OSError, ValueError, KeyError, TypeError):
            errors.append(f"{rel.as_posix()}: 工具定义缺失或格式无效")
            continue
        if tool["name"] != "save_class_notes":
            errors.append(f"{rel.as_posix()}: 可编辑写作要求不得改变笔记保存工具")
        if any(word in json.dumps(tool, ensure_ascii=False) for word in ("不添加额外知识", "只依据课堂来源整理的正文")):
            errors.append(f"{rel.as_posix()}: 工具说明不能重复限定用户可编辑的写作风格")
    client = Path("app/src/main/java/io/github/nahanhhan/lecturerecording/cloud/CloudClient.kt")
    try:
        text = (root / client).read_text(encoding="utf-8")
    except OSError:
        errors.append(f"{client.as_posix()}: 请求客户端文件不可读取")
        return errors
    if '"请求正文=${CloudRequests.loggable(adapted)}"' not in text:
        errors.append(f"{client.as_posix()}: 请求调试日志必须使用系统消息省略后的正文")
    return errors
