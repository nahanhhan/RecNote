# RecNote 远程识别协议 v1

本文于 2026-11-04 根据用户提供的 `RecNote-Sync.md` 更新为待适配的目标契约。附件描述 Voice2Note 方案已定稿，但未附其完整设计及协议夹具，本文不据此宣称电脑服务已部署。现有 RecNote 代码和已交付 `a8e53ee` APK 使用旧契约，尚需完成 [任务第 7 组](../openspec/changes/add-remote-compute-mode/tasks.md)。历史工程验证见 [verification.md](../openspec/changes/add-remote-compute-mode/verification.md)。

## 连接与路径

控制接口相对电脑基础地址追加 `api/v1/`，保留基础地址已有前缀。控制请求／响应上限 64 KiB，禁止重定向，HTTPS 使用系统证书校验。连接 token 用于 health、probe 和 start，任务 token 用于 end、status、retry、cancel；凭据不进入云盘或日志。

双端 WebDAV 根对应同一真实文件夹。**所有协议路径一律相对云盘根**，不以 `/` 开头，不含协议前缀、反斜杠或 `.`／`..` 路径段。任务音频和原稿必须属于当前 `session_id`。start 中 `cloud_path=RecNoteRemote/<session_id>` 是任务目录；音频 path 为 `RecNoteRemote/<session_id>/audio/<六位编号>.wav`，原稿 path 为 `RecNoteRemote/<session_id>/.transcripts/<六位编号>.md`。手机不能重复拼接任务根或 `.transcripts/`。

音频和原稿走云盘，控制接口发送任务资料。手机和电脑的 localhost 各指自身设备，实际云盘传输路由仍需部署验收。

## 控制接口

| 接口 | 输入 | 响应及作用 |
| --- | --- | --- |
| `GET health` | Bearer 连接 token | `protocol_version=1, asr_ready, openlist_ready`；连接成功还须 probe 的双向回执校验 |
| `POST probe` | `token, path, sha256` | 返回下节完整字段；path 固定为 `RecNoteRemote/_probe/<uuid>.bin` |
| `POST sessions/start` | `protocol_version=1, token, session_id, cloud_path, title, source_type` | `session_id, session_token, state`；相同身份和目录幂等返回原任务与 token |
| `POST sessions/end` | 仅 `{"token":"<session_token>"}` | token 合法恒成功，重复也成功；等待最终清单和迟到块，不设合法 end 的 409 分支 |
| `GET sessions/<session_id>` | Bearer 任务 token | `session_id, state`；state 仅为 `waiting_source, receiving, draining, completed, failed, cancelled` |
| `POST sessions/retry` | 仅任务 `token` | 补足失败的下载／识别／回写，保留成功块 |
| `POST sessions/cancel` | 仅任务 `token` | 停止任务并删除电脑本地工作目录，云盘保留；已完成任务也清理并转为 `cancelled` |

`source_type` 为 `microphone` 或 `import`。同标识不同身份／路径返回 `409 session_id_conflict`；已取消任务再次 start 返回 `409 session_terminal`。后者表示需为新录音使用新 `session_id`，手机不反复重试旧 start，也不展示通用异常弹窗。电脑忽略请求体未知字段，仍校验必填字段存在与类型；手机 v1 只依赖已经列明的字段。

## 双向探测与回执

1. 手机写入独立合成文件 `RecNoteRemote/_probe/<uuid>.bin`，path 恰好三段，相对云盘根。
2. 手机 POST probe，字段为 `token, path, sha256`，不使用 `cloud_path` 表示探测文件。
3. 电脑读入并核验探测文件，写回同目录的 `<探测路径>.receipt.json`。
4. 响应字段为 `protocol_version, ok, asr_ready, model_loaded, read_ok, write_ok, receipt{path, sha256}, error`。HTTP 状态表示请求合法性，就绪按响应体布尔值判断。
5. 手机要求协议匹配及 `ok/asr_ready/read_ok/write_ok` 通过；核对返回的回执 path 恰为本次预期路径，回读其确切字节，核对 SHA-256 与响应 `receipt.sha256` 相同；再验证回执内部的协议版本和探测文件校验值绑定本次探测。
6. `model_loaded=false` 表示尚未加载识别模型，提示首块可能较慢；其余检查通过时不单独判失败。HTTP 成功但 `ok=false` 直接显示原因，不进入退避重试。

回执内容包含协议版本、探测文件校验值、识别就绪与时间；具体内部字段名和时间格式须取得对端样例后锁定。响应 `receipt.sha256` 是回执文件摘要，不是原探测文件摘要。只读挂载、回执缺失／半写、摘要不匹配均不能报告连接成功。测试清理只涉及本次探测文件和回执。

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
RecNoteRemote/_probe/<uuid>.bin
RecNoteRemote/_probe/<uuid>.bin.receipt.json
```

`manifest.json`／`final.json` 公共字段保留 `protocol_version=1, session_id, revision, sample_rate=16000, channels=1, encoding="pcm_s16le", chunks`。音频条目固定为 `sequence, path, start_sample, sample_count, sha256`，删除 `bytes`。编号从 1 连续，`sample_count` 为必填采样帧数，供手机计算时间和电脑核对连续性。暂停不增加音频样本。目标每块约 30 秒，允许暂停／停止短尾块，空音频块不列入清单。

音频完整性由整个 WAV 文件字节（包含头部）的 SHA-256 判断，不依赖预报大小；电脑流式读取到配置体积上限即硬截断，返回不可重试的 `audio_too_large`。具体上限数值由对端配置／样例确认，不在手机文档中猜定。

滚动清单只列上传成功块，数据先写、清单后发布，revision 单调递增。`final.json` 额外含 `total_samples, chunk_count`，停止且全部音频上传后发布，并保留确切 UTF-8 JSON 字节，不重新序列化后校验摘要，不修改已发布的不可变集合。

`index.json` 保留 `protocol_version, session_id, revision, chunks`。结果条目沿用 `sequence, audio_sha256, status, path, bytes, sha256, error`，其中 path 改为云盘根相对路径。**本次仅删除音频条目的 bytes**；结果字段最终以对端原样例再核对。块级 status 与接口 state 是不同字段，既有块级 `pending, processing, completed, failed` 不因接口 state 改名而扩展成六态。

识别结果文本加一个结尾换行形成 UTF-8 原稿，统一以单个换行结尾；静音块原稿为一字节 `\n` 文件。手机先按原始字节验摘要，再去首尾空白判空，不能将静音误判成缺文件。块内全部语句完成且原稿回写成功，才发布整块 completed；手机按本地音频样本范围映射块级来源段落和回听。

`complete.json` 保留 `protocol_version, session_id, final_manifest_sha256, chunks`，绑定收到的原始最终清单字节和完整块结果指纹。只有最终集合、索引及全部已保存原稿相符才标记转写完成；接口 state=completed 本身不足以证明手机已取回全部文字。编辑后重复刷新不覆盖本地内容。

清单／索引／完成标记保留 2 MiB 接收上限，原稿保留 128 KiB 上限；原始文件 SHA-256 使用小写十六进制。拒绝跨任务路径、任意 URL、非法点路径和未知块；半写、暂时 404、旧索引或缺原稿时保留已验证缓存，等待刷新。云盘无需支持原子 MOVE。

## 错误与重试

错误继续使用 `code/message/retryable`，展示须脱敏。有效错误体的自动重试决策服从 `retryable`，不按 HTTP 状态自行猜测。无网络等待网络；无有效响应的暂时连接失败有限退避；连续五次可重试失败转人工。缺失／损坏结构化错误体提示人工检查。probe 的 `ok=false` 不作为退避故障，`audio_too_large` 必须不可重试且与格式错误分别呈现。

| 类别 | 错误码 |
| --- | --- |
| 接口层 | `invalid_request`, `invalid_cloud_path`, `invalid_path`, `unauthorized`, `forbidden`, `unknown_session`, `not_found`, `session_id_conflict`, `session_terminal`, `payload_too_large`, `unsupported_protocol_version`, `too_many_requests`, `internal_error`, `asr_not_ready`, `storage_not_ready` |
| 云盘／处理 | `audio_missing`, `audio_sha256_mismatch`, `audio_format_invalid`, `audio_too_large`, `audio_decode_failed`, `asr_failed`, `transcript_upload_failed`, `manifest_invalid`, `probe_missing`, `probe_sha256_mismatch`, `probe_too_large` |

## 配置、取消与恢复

电脑服务配置为 `rec-daemon.yaml`，与批处理 `config.yaml` 分开，包含服务／存储／队列／体积限制／工作目录配置，不含模型字段。字段名和配置上限须从对端资料确认。手机模型整理配置保持原流程，不进入电脑服务配置。

记录识别模式在创建时固定；电脑或网盘连接改变使旧任务暂停，恢复原配置后手动重试。笔记同步开关独立于音频上传。手机重启恢复封存块和控制通知，不自动开麦或冻结中断录音的最终集合。

取消含电脑本地清理，已完成任务同样可取消。手机删除记录前须持久保存独立取消发件箱，保留绑定原连接的身份及加密 token，删除后仍可补发，确认后再清除；连接改变时暂停旧通知，迟到回执不重建录音。云盘副本继续保留。

## 样例与兼容状态

待取得 Voice2Note `tests/fixtures/protocol_v1/` 的滚动清单、最终清单、结果索引、完成标记四份 JSON 和字段说明，原样复制至 RecNote 测试资源并用 Kotlin 断言字段；另补 probe 响应／回执样例。当前附件只有同步清单，没有这些原文件；文档中的约定不是对端样例验证结果。

新适配必须覆盖旧持久任务的迁移：有本地音频的未完成记录可经用户明确选择新建任务重发；不得原地改写已发布 final.json。代码、模拟服务和夹具更新后重跑 CI，再执行真实联调。本次文档更新不生成新 APK，也不使旧 APK 自动兼容。
