# Voice2Note 与 RecNote 远程算力协作备忘录

本文件按用户指定命名为 `Vec2Note_todo.md`，协作对象是 Voice2Note。对应 [提案](openspec/changes/add-remote-compute-mode/proposal.md) 与 [设计](openspec/changes/add-remote-compute-mode/design.md)。2026-11-04 根据用户提供的 `RecNote-Sync.md` 对齐电脑侧定稿约定；附件引用的 `add-rec-daemon` 设计和 `V2N-RecDaemon.md` 未随附，不能据此确认服务已实现或部署。当前 RecNote 代码和既有测试 APK 仍待适配。

## 1. 协作流程

1. 手机采集／解码并封存约 30 秒完整音频，经本机 OpenList 上传云盘。
2. 手机发送开始请求，携带连接 token、独立任务标识和相对云盘根的任务目录；电脑签发独立任务 token 并主动轮询音频。
3. 电脑内部按句处理，每个大块完成后回写完整原稿与结果索引。
4. 手机停止时发送只有任务 token 的结束请求；电脑继续等最终清单和迟到音频，全部回写才发布完成标记。
5. 手机进入详情或手动刷新时取回完整原稿并核验最终集合；每块原稿映射为一个本地来源段落。
6. 手机删除任务时持久发送取消；电脑停止并清理本地工作目录，云盘副本保留。

电脑负责音频识别和原稿回写。公开 API 承载控制请求，音频及原稿经云盘中转。

## 2. 服务与配置

原参考提交 `c7e837c7563e95c224e021bb8000b9e0b4fb64e4` 的批处理入口与 `transcribe` 复用点属于历史调研。后续对接以电脑端 `add-rec-daemon` 定稿及原样例为准；RecNote 只实施手机客户端。

电脑服务配置名为 **`rec-daemon.yaml`**，与批处理的 `config.yaml` 分离。服务配置包含监听、鉴权、OpenList、队列、流式体积上限、状态及本地工作目录，不含模型字段。具体配置字段名与体积上限由电脑端资料确认，不沿用先前提议的配置模板作为已定稿事实。

双端 WebDAV 根须对应同一真实云盘文件夹。`127.0.0.1` 各指自身设备；手机只发送根内相对路径，电脑拼接自己的云盘根。实际上传／下载路由在部署时核验。连接凭据保存在各自设备，任务 token 本地加密，不写云盘或日志。

## 3. 路径与文件

所有协议路径统一相对云盘根，禁止前导 `/`、协议前缀、反斜杠和点路径。任务根为 `RecNoteRemote/<session_id>`。

| 对象 | 路径或字段 |
| --- | --- |
| 音频块 path | `RecNoteRemote/<session_id>/audio/000001.wav` |
| 原稿 path | `RecNoteRemote/<session_id>/.transcripts/000001.md` |
| 音频清单 | 任务根内 `manifest.json` 与不可变 `final.json` |
| 原稿索引／完成 | 任务根内 `.transcripts/index.json` 与 `.transcripts/complete.json` |
| 探测文件 | `RecNoteRemote/_probe/<uuid>.bin`，恰好三段 |
| 探测回执 | `<探测路径>.receipt.json` |

音频条目为 `sequence, path, start_sample, sample_count, sha256`，删除 `bytes`。`sample_count` 是必填采样帧数，时间按连续样本累计，暂停不增加样本。SHA-256 覆盖整个 WAV 文件含头部；电脑在流式读取时执行配置体积硬上限，超限使用 `audio_too_large` 且不可重试。

清单公共字段保留 `protocol_version, session_id, revision, sample_rate=16000, channels=1, encoding=pcm_s16le, chunks`；最终清单增加 `total_samples, chunk_count`，绑定完整连续样本集合。数据先写、索引后发布，revision 单调递增；最终清单摘要基于确切原始 UTF-8 字节。

原稿索引公共字段保留 `protocol_version, session_id, revision, chunks`，结果条目沿用 `sequence, audio_sha256, status, path, bytes, sha256, error`，结果 path 改为云盘根相对路径。本次只删除音频条目 bytes，原稿结果字段待取得对端样例再次核对。原稿统一为识别文本加单个结尾换行，静音文件为一字节 `\n`；手机按原字节核验后去首尾空白判空。

完成标记保留 `protocol_version, session_id, final_manifest_sha256, chunks`，手机核对最终清单和全部已保存原稿才标记完成。清单／索引接收上限保留 2 MiB，原稿保留 128 KiB；半写、缺失或校验不完整时等待／刷新，保留既有有效结果。

## 4. 控制接口

协议 v1，接口相对电脑基础地址，控制请求／响应上限 64 KiB，禁止重定向。

| 接口 | 输入 | 约定 |
| --- | --- | --- |
| `GET /api/v1/health` | Bearer 连接 token | 版本与服务就绪诊断 |
| `POST /api/v1/probe` | `token, path, sha256` | 读入手机探测文件，写回回执；手机回读并校验响应 `receipt.sha256` |
| `POST /api/v1/sessions/start` | `protocol_version, token, session_id, cloud_path, title, source_type` | 签发独立 `session_token`，幂等复用同身份任务 |
| `POST /api/v1/sessions/end` | 仅 `{ "token": "<session_token>" }` | 合法 token 恒成功，重复同样成功，等待最终清单 |
| `GET /api/v1/sessions/<session_id>` | Bearer 任务 token | 任务诊断；URI 标识与 token 绑定 |
| `POST /api/v1/sessions/retry` | 仅任务 `token` | 补足失败块和回写，保留已完成项 |
| `POST /api/v1/sessions/cancel` | 仅任务 `token` | 停止并清理电脑本地工作目录，已完成任务也转为 cancelled，云盘保留 |

接口 state 仅使用 `waiting_source, receiving, draining, completed, failed, cancelled`。同标识不同身份／路径返回 `409 session_id_conflict`；已取消任务再次 start 返回 `409 session_terminal`，手机提示使用新任务。未知请求字段忽略，必填字段检查存在和类型，v1 不依赖未列明字段。

probe 响应为 `protocol_version, ok, asr_ready, model_loaded, read_ok, write_ok, receipt{path, sha256}, error`。手机核对本次回执 path、实际回执文件摘要和回执内部的探测文件校验值；就绪及双向读写通过才成功。`model_loaded=false` 提示首块较慢，`ok=false` 显示原因而不自动退避。回执内部字段名与时间格式待对端样例确认。

错误采用脱敏的 `code/message/retryable`，重试服从 `retryable`，不能仅凭 HTTP 状态推断。完整接口和云盘错误码见 [协议说明](docs/remote-asr-protocol.md#错误与重试)，其中 `audio_too_large` 与 `audio_format_invalid` 分开呈现。普通文件等待不计失败次数。

## 5. 待同步与联调检查

- 取得电脑端四份协议 JSON 原样例（滚动清单、最终清单、结果索引、完成标记）与字段说明，在 RecNote 测试资源中保留来源提交，使用 Kotlin 断言字段。
- 补齐 probe 响应／回执内部字段和音频体积上限资料；当前同步清单未附这些内容。
- 手机写入探测文件，电脑读入并验证；电脑写回回执，手机读回并核验；只读挂载不能测试成功。
- 手机发送完整块和最终集合，电脑识别并回写原稿；验证所有路径均相对云盘根且无重复拼接。
- 验证尾块迟到、空音频集合、一字节静音原稿、半写文件、错误码／可重试标志、重复通知和电脑重启。
- 验证手机删除后取消仍可恢复发送，电脑本地目录清理，云盘资料保留；已完成任务同样清理。
- 两台手机的任务、token、结果和取消互不影响；真实传输链路、温升／耗电和设备行为另行验收。

以上是协作约定及后续验收目标，不是电脑端或手机端已通过联调的声明。
