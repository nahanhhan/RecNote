"""下载源直链调研脚本（propose 阶段调研用，不注册进默认检查）。

对每个模型的 GitHub / ModelScope 直链发起 HEAD 与 Range GET（仅前 1KB）请求，
输出状态码、重定向链、Content-Length、Accept-Ranges、ETag，用于确认魔塔社区
直链可用且支持断点续传。绝不下载完整模型：HEAD 无响应体，Range GET 最多读 1KB。

运行方式：uv run python probe_download_sources.py
"""

from __future__ import annotations

import socket
import sys
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

socket.setdefaulttimeout(10)
TIMEOUT = 10

# 与 app/.../models/ModelCatalog.kt 中的归档保持一致
MODELS = [
    {
        "id": "aed",
        "archive": "sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26.tar.bz2",
        "bytes": 838589068,
        "modelscope": "adaada88/sherpa-onnx-fire-red-asr2-aed-zh-en-int8",
    },
    {
        "id": "ctc",
        "archive": "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.tar.bz2",
        "bytes": 520516278,
        "modelscope": "adaada88/sherpa-onnx-fire-red-asr2-ctc-zh-en-int8",
    },
]

GITHUB_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{archive}"
MODELSCOPE_URL = "https://modelscope.cn/models/{repo}/resolve/{branch}/{archive}"
BRANCHES = ["master", "main"]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """不自动跟随重定向，逐跳打印。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[override]
        return None


OPENER = urllib.request.build_opener(NoRedirect())


def one_request(url: str, method: str, headers: dict[str, str] | None = None):
    """单次请求，永不抛异常。返回 (status|None, headers, error|None)。"""
    req = urllib.request.Request(url, method=method, headers=headers or {})
    try:
        with OPENER.open(req, timeout=TIMEOUT) as resp:
            return resp.status, dict(resp.headers), None
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), None
    except Exception as error:  # 超时、连接被拒、TUN 黑洞等
        return None, {}, f"{type(error).__name__}: {error}"


def follow(url: str, method: str, headers: dict[str, str] | None = None):
    """跟随重定向链（最多 8 跳），返回 (最终状态, 最终头, 链, 错误)。"""
    chain: list[str] = []
    current, current_method = url, method
    for _ in range(8):
        status, hdrs, error = one_request(current, current_method, headers)
        if error is not None:
            return None, hdrs, chain + [f"{current} !! {error}"], error
        if status in (301, 302, 303, 307, 308):
            location = hdrs.get("Location") or hdrs.get("location")
            if not location:
                return status, hdrs, chain, None
            chain.append(f"{current} -> {status} {location}")
            current = location
            if status == 303:
                current_method = "GET"
            continue
        return status, hdrs, chain, None
    return None, {}, chain, "重定向超过 8 跳"


def probe(label: str, url: str, expect_bytes: int | None = None) -> bool:
    print(f"\n== {label}", flush=True)
    print(f"   URL: {url}", flush=True)
    ok = True

    status, hdrs, chain, error = follow(url, "HEAD")
    for hop in chain:
        print(f"   hop: {hop}", flush=True)
    if error is not None:
        print(f"   [FAIL] HEAD 请求失败：{error}", flush=True)
        return False
    length = hdrs.get("Content-Length") or hdrs.get("content-length") or "-"
    accept = hdrs.get("Accept-Ranges") or hdrs.get("accept-ranges") or "-"
    etag = hdrs.get("ETag") or hdrs.get("etag") or "-"
    print(f"   HEAD: status={status} content-length={length} accept-ranges={accept} etag={etag}", flush=True)
    if status not in (200, 206):
        print("   [FAIL] HEAD 未返回 200/206", flush=True)
        ok = False
    if expect_bytes is not None and length not in ("-", str(expect_bytes)):
        print(f"   [WARN] Content-Length 与预期 {expect_bytes} 不一致", flush=True)
    if accept.lower() != "bytes":
        print("   [WARN] 未声明 Accept-Ranges: bytes（续传可能不可用）", flush=True)

    # Range GET：只读前 1KB 后立即关闭，绝不下载完整模型
    req = urllib.request.Request(url, method="GET", headers={"Range": "bytes=0-1023"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            status, hdrs, read = resp.status, dict(resp.headers), resp.read(1024)
    except urllib.error.HTTPError as error:
        status, hdrs, read = error.code, dict(error.headers), b""
    except Exception as error:
        print(f"   [FAIL] Range GET 请求失败：{type(error).__name__}: {error}", flush=True)
        return False
    content_range = hdrs.get("Content-Range") or hdrs.get("content-range") or "-"
    print(f"   RANGE GET: status={status} content-range={content_range} read={len(read)}B", flush=True)
    if status != 206:
        print("   [FAIL] Range 请求未返回 206，无法断点续传", flush=True)
        ok = False
    return ok


def main() -> int:
    failures = 0
    for model in MODELS:
        archive = model["archive"]
        if not probe(f"[{model['id']}] GitHub", GITHUB_URL.format(archive=archive), model["bytes"]):
            failures += 1
        found = False
        for branch in BRANCHES:
            url = MODELSCOPE_URL.format(repo=model["modelscope"], branch=branch, archive=archive)
            status, _, chain, error = follow(url, "HEAD")
            if error is None and status in (200, 206):
                if not probe(f"[{model['id']}] ModelScope ({branch})", url, model["bytes"]):
                    failures += 1
                found = True
                break
            detail = error or f"status={status}"
            print(f"\n== [{model['id']}] ModelScope ({branch}) -> {detail}（跳过）", flush=True)
        if not found:
            print(f"[FAIL] [{model['id']}] ModelScope master/main 分支均不可用", flush=True)
            failures += 1
    print(f"\n调研完成，失败项：{failures}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())