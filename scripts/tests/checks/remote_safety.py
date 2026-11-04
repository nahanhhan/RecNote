"""远程通道凭据与日志边界的只读自检；行为验证由 Kotlin 测试承担。"""
from pathlib import Path
import re


def run(root: Path) -> list[str]:
    base = root / "app/src/main/java/io/github/nahanhhan/lecturerecording"
    errors = []
    settings = (base / "data/SettingsStore.kt").read_text(encoding="utf-8")
    if 'putString("remote_token_cipher", encrypt(saved.token))' not in settings:
        errors.append("SettingsStore.kt: 远程凭据必须加密保存")
    for path in (base / "remote").glob("*.kt"):
        text = path.read_text(encoding="utf-8")
        if re.search(r"AppLog\.[ide]\(", text):
            errors.append(f"{path.relative_to(root)}: 远程通道不应记录请求或服务端正文")
    client = (base / "remote/RemoteControlClient.kt").read_text(encoding="utf-8")
    if ".followRedirects(false)" not in client or ".followSslRedirects(false)" not in client:
        errors.append("RemoteControlClient.kt: 控制请求必须禁止重定向")
    return errors
