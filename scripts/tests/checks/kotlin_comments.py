"""Kotlin 注释配对检查：块注释（可嵌套）与字符串字面量正确闭合。

在无 Kotlin 环境的端侧提前发现 "Unclosed comment" 类编译错误：
Kotlin 块注释支持嵌套，注释文本里的 `/*` 会加深层级，需要配对的 `*/` 才能闭合。
"""

from __future__ import annotations

from pathlib import Path

SOURCE_DIRS = (Path("app/src"), Path("core/src"))


def _comment_errors(text: str) -> list[str]:
    errors: list[str] = []
    i, n = 0, len(text)
    line = 1
    depth = 0          # 块注释嵌套深度
    open_line = 1      # 最外层块注释起始行
    brace_depth = 0    # 花括号深度（用于识别字符串模板 ${...} 的结束）
    templates: list[int] = []
    mode = "code"      # code | line | block | string | raw | char
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
        if mode == "code":
            if c == "/" and nxt == "/":
                mode = "line"; i += 2; continue
            if c == "/" and nxt == "*":
                if depth == 0:
                    open_line = line
                depth += 1; mode = "block"; i += 2; continue
            if c == '"' and text[i:i + 3] == '"""':
                mode = "raw"; i += 3; continue
            if c == '"':
                mode = "string"; i += 1; continue
            if c == "'":
                mode = "char"; i += 1; continue
            if c == "{":
                brace_depth += 1
            elif c == "}":
                if templates and brace_depth == templates[-1]:
                    templates.pop()
                    mode = "string"
                brace_depth -= 1
            i += 1; continue
        if mode == "line":
            if c == "\n":
                mode = "code"
            i += 1; continue
        if mode == "block":
            if c == "/" and nxt == "*":
                depth += 1; i += 2; continue
            if c == "*" and nxt == "/":
                depth -= 1
                if depth == 0:
                    mode = "code"
                i += 2; continue
            i += 1; continue
        if mode == "string":
            if c == "\\":
                i += 2; continue
            if c == "$" and nxt == "{":
                brace_depth += 1
                templates.append(brace_depth)
                mode = "code"; i += 2; continue
            if c == '"':
                mode = "code"; i += 1; continue
            i += 1; continue
        if mode == "raw":
            if text[i:i + 3] == '"""':
                mode = "code"; i += 3; continue
            i += 1; continue
        if mode == "char":
            if c == "\\":
                i += 2; continue
            if c == "'":
                mode = "code"; i += 1; continue
            i += 1; continue
    if depth > 0:
        errors.append(f"第 {open_line} 行的块注释未闭合（剩余嵌套深度 {depth}）")
    if mode == "raw":
        errors.append("原始字符串（\"\"\"）未闭合")
    if mode in ("string", "char"):
        errors.append(f"第 {line} 行（文件结尾）字符串/字符字面量未闭合")
    return errors


def run(root: Path) -> list[str]:
    errors: list[str] = []
    for source_dir in SOURCE_DIRS:
        base = root / source_dir
        if not base.is_dir():
            errors.append(f"{source_dir.as_posix()}: 目录不存在")
            continue
        for path in sorted(base.rglob("*.kt")):
            rel = path.relative_to(root).as_posix()
            for message in _comment_errors(path.read_text(encoding="utf-8")):
                errors.append(f"{rel}: {message}")
    return errors
