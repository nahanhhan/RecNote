"""WebDAV 安全边界的提交前只读检查；协议与恢复行为由 CI 的 Kotlin 单测验证。"""
from __future__ import annotations

import re
from pathlib import Path


def run(root: Path) -> list[str]:
    errors: list[str] = []
    base = Path("app/src/main/java/io/github/nahanhhan/lecturerecording")
    settings = (root / base / "data/SettingsStore.kt").read_text(encoding="utf-8")
    for name in ("user", "password"):
        if not re.search(rf'putString\("webdav_{name}_cipher",\s*encrypt\(', settings):
            errors.append(f"{base}/data/SettingsStore.kt: 网盘 {name} 必须以加密密文保存")
    for source in sorted((root / base / "sync").glob("*.kt")):
        text = source.read_text(encoding="utf-8")
        for match in re.finditer(r'AppLog\.[ide]\([^\n]*', text):
            if re.search(r"password|username|Authorization|credentials|baseUrl|response\.body", match.group(), re.I):
                errors.append(f"{source.relative_to(root).as_posix()}: 网盘日志不得包含凭据、地址或服务端正文")
    core_path = Path("core/src/main/kotlin/io/github/nahanhhan/lecture/core/WebDavSync.kt")
    core = (root / core_path).read_text(encoding="utf-8")
    ticket = re.search(r"data class NoteUpload\((.*?)\n\)", core, re.S)
    if ticket is None or re.search(r"val\s+(?:password|username|key|baseUrl|authorization)\b", ticket.group(1), re.I):
        errors.append(f"{core_path.as_posix()}: 上传任务元数据不得保存凭据")
    client_path = base / "sync/WebDavClient.kt"
    client = (root / client_path).read_text(encoding="utf-8")
    if ".followRedirects(false)" not in client or ".followSslRedirects(false)" not in client:
        errors.append(f"{client_path.as_posix()}: 网盘请求必须禁止凭据随重定向发送")
    download_path = base / "models/ModelDownloadService.kt"
    if ".followSslRedirects(false)" not in (root / download_path).read_text(encoding="utf-8"):
        errors.append(f"{download_path.as_posix()}: 下载必须拒绝 HTTPS 降级到 HTTP")
    return errors
