# RecNote 远程识别协议 v1

本文件定义 RecNote 客户端已实现的对接格式，供后续电脑端联调使用。实现与验收状态见 [verification.md](../openspec/changes/add-remote-compute-mode/verification.md)。本轮只修改 RecNote。

## 连接与路径

控制接口相对电脑基础地址追加 `api/v1/`，保留基础地址已有路径前缀。请求/响应最多 64 KiB，禁止重定向；HTTPS 使用系统证书校验。连接 token 只用于 health、probe 和 start，任务 token 用于 end、status、retry 和 cancel；均不进入云盘文件或日志。错误只显示脱敏原因及 HTTP 状态。

双端 WebDAV 根对应同一真实文件夹。每份录音固定 `cloud_path=RecNoteRemote/<session_id>`，`session_id` 由手机生成 UUID；音频和原稿走云盘，不随控制请求发送。手机与电脑的 localhost 分别指各自设备。真实 OpenList 的上传/下载路由需在后续部署中核验。

## 控制接口

| 接口 | 输入 | 成功响应及作用 |
| --- | --- | --- |
| `GET health` | Bearer 连接 token | `{"protocol_version":1,"asr_ready":true,"openlist_ready":true}` |
| `POST probe` | `token, cloud_path, sha256` | `{"ok":true}`；path 指手机新建的 `RecNoteRemote/probe-<uuid>/probe.json` 合成探测文件，电脑须读取并核验 |
| `POST sessions/start` | `protocol_version=1, token, session_id, cloud_path, title, source_type` | `session_id, session_token, state`；同身份、同路径重试返回原任务和同一 token |
| `POST sessions/end` | 仅 `{"token":"<session_token>"}` | JSON 对象（可为 `{}`）；进入收尾，继续等最终音频集合 |
| `GET sessions/<session_id>` | Bearer 任务 token | `session_id, state`；支持 `waiting_source, receiving, draining, asr_completed, failed, cancelled` |
| `POST sessions/retry` | 仅任务 `token` | JSON 对象；补足失败的下载、识别或回写，保留已完成块 |
| `POST sessions/cancel` | 仅任务 `token` | JSON 对象；停止对应任务，保留云盘副本 |

`source_type` 为 `microphone` 或 `import`。相同标识配不同身份或路径应返回 409。401/403、参数或协议错误停止自动重试；408/429/5xx、暂时连接错误有限退避，连续五次失败后由用户手动重试。无网络由网络约束等待；普通文件尚未出现不算控制请求失败。完成识别状态不代表手机已取回全部原稿。

## 云盘文件

```text
RecNoteRemote/<session_id>/
  audio/000001.wav
  audio/000002.wav
  manifest.json
  final.json
  .transcripts/
    000001.md
    000002.md
    index.json
    complete.json
```

`manifest.json` 和 `final.json` 公共字段为 `protocol_version=1, session_id, revision, sample_rate=16000, channels=1, encoding="pcm_s16le", chunks`。每个音频条目含 `sequence, path, start_sample, sample_count, bytes, sha256`。编号从 1 连续递增，path 固定 `audio/<六位编号>.wav`，起点按有效音频样本连续累计，暂停不增加样本。PCM16 WAV 使用 44 字节头；手机目标约 30 秒，协议上限 31 秒，允许短尾块。空块不列入清单。

滚动清单仅列已上传块，数据先传、清单后传。`final.json` 额外包含 `total_samples` 和 `chunk_count`，只在停止且全部块已上传后发布，不可变。手机保留确切 JSON 字节用于校验；对端必须校验收到的原始字节，不重新排列字段后计算摘要。

`index.json` 含 `protocol_version=1, session_id, revision, chunks`。每个结果条目含 `sequence, audio_sha256, status, path, bytes, sha256, error`。status 使用 `pending, processing, completed, failed`；完成条目 path 固定 `<六位编号>.md`，相对于 `.transcripts/`，并绑定对应音频 SHA-256。内部按句识别全部完成且原稿上传成功后，才发布整块 completed。静音块仍生成原稿文件，正文可为空。手机以完整块为来源段落，回听时间来自对应音频样本范围。

`complete.json` 含 `protocol_version=1, session_id, final_manifest_sha256, chunks`。chunks 使用与索引一致的完整结果条目，全部为 completed；必须覆盖最终集合，每项输入/原稿指纹、路径与大小均与索引一致。手机取回、核验并保存全部原稿后才标记转写完成。重复刷新保持原稿编辑内容。

清单、索引和完成标记最多 2 MiB；单份原稿最多 128 KiB，正文必须为有效 UTF-8。SHA-256 使用实际文件字节和小写十六进制。拒绝任务外路径、任意 URL、未知块和内容冲突；半写 JSON、暂时 404、旧索引或未到齐原稿保留已验证缓存，等待刷新。云盘无需支持原子 MOVE。

## 配置与恢复

识别方式在新记录创建时固定。电脑地址/token 和 WebDAV 地址/账号变化会使旧队列暂停，恢复原配置后需手动重试。仅关闭笔记同步不影响音频队列。手机重启恢复已封存音频和控制通知；异常中断的录音由用户继续或结束，不自动发布可能截断录音的最终清单。任务停止后不允许追加音频。

取消远程处理后停止本地上传和结果接收，电脑取消通知尽力发送；可删除的本地记录移除任务状态和缓存，云盘副本保留。
