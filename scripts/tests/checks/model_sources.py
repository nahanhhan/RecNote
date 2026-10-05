"""下载源检查：ModelCatalog 仓库地址与 URL 拼接、DownloadSource 回落、按源续传文件命名。"""

from __future__ import annotations

import re
from pathlib import Path

MODELS_DIR = Path("app/src/main/java/io/github/nahanhhan/lecturerecording/models")
CATALOG = MODELS_DIR / "ModelCatalog.kt"
SOURCE_ENUM = MODELS_DIR / "DownloadSource.kt"
SERVICE = MODELS_DIR / "ModelDownloadService.kt"
SETTINGS = Path("app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt")

GITHUB_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
MODELSCOPE_BASE = "https://modelscope.cn/models/"

# 与用户确认的魔塔社区仓库及 GitHub Releases 归档保持一致
EXPECTED = {
    "aed": (
        "adaada88/sherpa-onnx-fire-red-asr2-aed-zh-en-int8",
        "sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26.tar.bz2",
    ),
    "ctc": (
        "adaada88/sherpa-onnx-fire-red-asr2-ctc-zh-en-int8",
        "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2",
    ),
}


def _read(root: Path, path: Path, errors: list[str]) -> str:
    full = root / path
    if not full.is_file():
        errors.append(f"{path.as_posix()}: 文件不存在")
        return ""
    return full.read_text(encoding="utf-8")


def run(root: Path) -> list[str]:
    errors: list[str] = []

    catalog = _read(root, CATALOG, errors)
    if catalog:
        blocks = catalog.split("ModelSpec(")[1:]
        seen: dict[str, tuple[str, str]] = {}
        for block in blocks:
            if "setOf(" not in block:  # 跳过 data class 声明块，只处理模型定义
                continue
            strings = re.findall(r'"([^"]+)"', block)
            if len(strings) < 3:
                continue
            model_id = strings[0]
            if model_id not in EXPECTED:
                errors.append(f"{CATALOG.as_posix()}: 未知模型 id {model_id}")
                continue
            archive = strings[2]
            modelscope = next((s for s in strings if s.startswith("adaada88/")), "")
            seen[model_id] = (modelscope, archive)
        for model_id, (repo, archive) in EXPECTED.items():
            if model_id not in seen:
                errors.append(f"{CATALOG.as_posix()}: 缺少模型 {model_id}")
                continue
            got_repo, got_archive = seen[model_id]
            if got_repo != repo:
                errors.append(f"{CATALOG.as_posix()}: {model_id} 魔塔仓库 {got_repo} != {repo}")
            if got_archive != archive:
                errors.append(f"{CATALOG.as_posix()}: {model_id} 归档 {got_archive} != {archive}")
            modelscope_url = f"{MODELSCOPE_BASE}{repo}/resolve/master/{archive}"
            github_url = f"{GITHUB_BASE}{archive}"
            if not modelscope_url.startswith(MODELSCOPE_BASE) or not github_url.startswith(GITHUB_BASE):
                errors.append(f"{CATALOG.as_posix()}: {model_id} 直链拼接结果异常")
        if "https://modelscope.cn/models/$modelscope/resolve/$modelscopeBranch/$archive" not in catalog:
            errors.append(f"{CATALOG.as_posix()}: 缺少魔塔直链模板")
        if f"{GITHUB_BASE}$archive" not in catalog:
            errors.append(f"{CATALOG.as_posix()}: 缺少 GitHub 直链模板")

    source_enum = _read(root, SOURCE_ENUM, errors)
    if source_enum:
        if "fun fromStorage" not in source_enum or "?: GITHUB" not in source_enum:
            errors.append(f"{SOURCE_ENUM.as_posix()}: 缺少未知值回落 GITHUB 的解析逻辑")

    settings = _read(root, SETTINGS, errors)
    if settings:
        if '"download_source"' not in settings:
            errors.append(f"{SETTINGS.as_posix()}: 缺少 download_source 偏好键")
        if "DownloadSource.fromStorage" not in settings:
            errors.append(f"{SETTINGS.as_posix()}: 下载源偏好未走回落解析")

    service = _read(root, SERVICE, errors)
    if service:
        for needle, why in [
            ("graph.settings.downloadSource", "未从偏好取下载源"),
            ("${model.id}.${source.storage}.part", "部分文件未按源命名"),
            ("${model.id}.${source.storage}.etag", "ETag 未按源命名"),
            ("model.urlFor(source)", "未按源取下载地址"),
            ('File(root, "${model.id}.part").delete()', "未清理旧格式部分文件"),
            ('File(root, "${model.id}.etag").delete()', "未清理旧格式 ETag"),
        ]:
            if needle not in service:
                errors.append(f"{SERVICE.as_posix()}: {why}")
    return errors