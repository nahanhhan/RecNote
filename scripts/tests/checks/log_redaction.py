"""日志脱敏规则检查：从 core 的 Logging.kt 提取凭据掩码正则并执行行为样本。

正则的单一来源是 Kotlin 源码；本检查提取其字符串字面量后用 Python 正则
执行同一批凭据样本，验证 Info 档掩码隐藏凭据原文且保留可读前缀，
避免规则被改坏后直到 CI 才暴露。
"""

from __future__ import annotations

import re
from pathlib import Path

LOGGING_FILE = Path("core/src/main/kotlin/io/github/nahanhhan/lecture/core/Logging.kt")

_KOTLIN_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
_KOTLIN_UNESCAPE = {
    "\\": "\\", '"': '"', "'": "'", "n": "\n", "t": "\t",
    "r": "\r", "b": "\b", "$": "$", "`": "`",
}

_SECRET_SAMPLES = [
    ("save api_key=sk-abcDEF1234567890 now", "sk-abcDEF1234567890"),
    ("token=ghp_0123456789abcdef ok", "ghp_0123456789abcdef"),
    ("Authorization: Bearer eyJhbGciOi.abc", "eyJhbGciOi.abc"),
    ('secret="p@ss w0rd"', "p@ss w0rd"),
    ("password: hunter2secret", "hunter2secret"),
    ("free-standing sk-abcDEF1234567890 tail", "sk-abcDEF1234567890"),
]

_READABLE_SAMPLES = [
    ("save api_key=sk-abcDEF1234567890 now", "api_key=***"),
    ("Authorization: Bearer eyJhbGciOi.abc", "Bearer ***"),
]


def _unescape(text: str) -> str:
    out: list[str] = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            out.append(_KOTLIN_UNESCAPE.get(text[i + 1], text[i + 1]))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _extract_regexes(source: str) -> dict[str, str]:
    """提取 `val <name> = Regex("..." + "...")` 的模式文本（支持字符串字面量拼接）。"""
    result: dict[str, str] = {}
    for match in re.finditer(r"val\s+(\w+)\s*=\s*Regex\(", source):
        pos = match.end()
        parts: list[str] = []
        while True:
            while pos < len(source) and source[pos] in " \t\r\n":
                pos += 1
            str_match = _KOTLIN_STRING.match(source, pos)
            if not str_match:
                break
            parts.append(_unescape(str_match.group(1)))
            pos = str_match.end()
            while pos < len(source) and source[pos] in " \t\r\n":
                pos += 1
            if pos < len(source) and source[pos] == "+":
                pos += 1
                continue
            break
        if parts:
            result[match.group(1)] = "".join(parts)
    return result


def _mask(text: str, patterns: dict[str, re.Pattern]) -> str:
    out = patterns["credentialKeyValue"].sub(lambda m: m.group(1) + "***", text)
    out = patterns["credentialBearer"].sub(lambda m: m.group(1) + "***", out)
    out = patterns["credentialSk"].sub("sk-***", out)
    return out


def run(root: Path) -> list[str]:
    rel = LOGGING_FILE.as_posix()
    path = root / LOGGING_FILE
    if not path.is_file():
        return [f"{rel}: 文件不存在"]
    errors: list[str] = []
    sources = _extract_regexes(path.read_text(encoding="utf-8"))
    required = ("credentialKeyValue", "credentialBearer", "credentialSk")
    patterns: dict[str, re.Pattern] = {}
    for name in required:
        if name not in sources:
            errors.append(f"{rel}: 未找到 {name} 正则声明")
            continue
        try:
            patterns[name] = re.compile(sources[name], flags=re.ASCII)
        except re.error as exc:
            errors.append(f"{rel}: {name} 正则无法解析 ({exc})")
    if len(patterns) != len(required):
        return errors
    for sample, secret in _SECRET_SAMPLES:
        masked = _mask(sample, patterns)
        if secret in masked:
            errors.append(f"{rel}: 掩码后仍出现凭据原文 \"{secret}\"（输入：{sample}）")
    for sample, expected in _READABLE_SAMPLES:
        masked = _mask(sample, patterns)
        if expected not in masked:
            errors.append(f"{rel}: 掩码结果缺少可读形态 \"{expected}\"（输入：{sample}）")
    return errors
