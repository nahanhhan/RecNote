# Voice2Note 与 RecNote 远程算力协作备忘录

本文件按用户指定命名为 `Vec2Note_todo.md`，协作对象是 [Voice2Note](https://github.com/zhangtony239/Voice2Note)。对应 RecNote 提案为 [add-remote-compute-mode](openspec/changes/add-remote-compute-mode/proposal.md)，完整技术约定见 [design.md](openspec/changes/add-remote-compute-mode/design.md)。这些是待实现约定，尚未宣称两端可联通。

## 1. 已确认的协作流程

1. 手机采集／解码并封存约 30 秒大切片，经手机本机 OpenList 上传云盘。
2. 手机用小型开始请求通知电脑，包含鉴权、任务标识和云盘相对路径；电脑通过自己的本机 OpenList 轮询下载。
3. V2N 对每个大切片使用已有按句算法，等整块完成后把原稿回写到 start 路径下 `.transcripts/`。
4. 手机停止时发送只有任务 token 的结束请求。电脑继续等最终清单和迟到文件，直到全部原稿写回。
5. 手机进入录音详情或用户点刷新，检查 `.transcripts/` 并取回完整块原稿；用户可核对、编辑。

电脑服务负责音频识别与原稿回写。公开电脑 API 承载控制请求，音频与原稿经云盘中转。

## 2. 已核对的 V2N 现状与改造位置

参考提交 `c7e837c7563e95c224e021bb8000b9e0b4fb64e4`，实现前再次检查该仓库的新提交：

| 已有模块 | 可复用能力 | 需要协同改动 |
| --- | --- | --- |
| [cli.py](https://github.com/zhangtony239/Voice2Note/blob/c7e837c7563e95c224e021bb8000b9e0b4fb64e4/src/voice2note/cli.py) | 文件批量转写入口 | 保留原 CLI；新增 ASR server 入口，直接复用识别函数 |
| [asr.py](https://github.com/zhangtony239/Voice2Note/blob/c7e837c7563e95c224e021bb8000b9e0b4fb64e4/src/voice2note/asr.py) | `transcribe`、内部按句切分、进程内懒加载模型、`save_transcript` | 直接接收已验证的完整大块；保存到独立任务目录，整块成功才宣布完成 |
| [config.py](https://github.com/zhangtony239/Voice2Note/blob/c7e837c7563e95c224e021bb8000b9e0b4fb64e4/src/voice2note/config.py) 与 [模板](https://github.com/zhangtony239/Voice2Note/blob/c7e837c7563e95c224e021bb8000b9e0b4fb64e4/src/voice2note/assets/config-template.yaml) | ASR 配置与路径校验基础 | 增加 ASR 专用服务模板与加载／校验路径，包含识别及服务／OpenList 字段 |

当前 `pyproject.toml` 没有 Web 服务组件。服务框架、HTTP 客户端和持久任务组件在 V2N 仓库选型并新增依赖；RecNote 不约束这些内部库。

## 3. 电脑 config.yaml 约定

服务 `config.yaml` 包含 ASR 所需的 `TORCH_BACKEND`、`ASR_LANGUAGE`、`DISABLE_MMAP`、原稿／音频工作路径，以及运行服务所必需的鉴权、OpenList 和队列配置。

新增配置建议如下（字段名是服务扩展提议，需在 V2N 模板和校验中落地；值均为示例）：

```yaml
SERVER_HOST: 127.0.0.1
SERVER_PORT: 8765
SERVER_AUTH_TOKEN_HASH: "<连接token的不可逆摘要>"
OPENLIST_WEBDAV_URL: "http://127.0.0.1:5244/dav/<电脑挂载>/共享目录"
OPENLIST_USERNAME: "<电脑本机OpenList账号>"
OPENLIST_PASSWORD: "<仅留在电脑的凭据>"
SESSION_STATE_PATH: ".server-state/"
SESSION_WORK_PATH: ".server-work/"
POLL_INTERVAL_SECONDS: 3
POLL_MAX_INTERVAL_SECONDS: 30
ASR_MAX_CONCURRENCY: 1
```

服务配置路径必须明确，避免长期后台启动时工作目录改变就读错 `config.yaml`。health 只显示 ASR 与云盘就绪情况，不返回配置正文或密钥。连接 token 可保存不可逆摘要；需要返回给幂等开始请求的任务 token 应在电脑本地加密保护，不写入云盘或日志。

手机和电脑的 WebDAV 根需映射到同一真实云盘文件夹。挂载名可以不同；`cloud_path` 是根内的相对路径，不携带任一端的 `127.0.0.1` 地址。初版建议双方 OpenList 使用本机代理，由本机访问真实存储；按 [OpenList 官方策略说明](https://docs.openlist.team/guide/drivers/common.html) 核查实际代理 URL 和上传／下载路径，不把绕开 cloudflared 当成未经验证的事实。

## 4. 必须实现的控制接口

协议 v1，所有接口相对于后端基础 URL。控制请求／响应限制 64 KiB，不自动重定向。

| 接口 | 输入 | 必须做到 |
| --- | --- | --- |
| `GET /api/v1/health` | Bearer 连接 token | 返回版本、能力与就绪状态，不创建实际任务 |
| `POST /api/v1/probe` | `token, cloud_path, sha256` | 从电脑 OpenList 验证手机写入的合成探测文件，限探测目录与小文件 |
| `POST /api/v1/sessions/start` | `protocol_version, token, session_id, cloud_path, title, source_type` | 签发独立 `session_token`，立刻建立云盘轮询；相同身份重试返回原任务与 token |
| `POST /api/v1/sessions/end` | 仅 `{ "token": "<session_token>" }` | 进入收尾，等待最终清单并完成全部原稿回写 |
| `GET /api/v1/sessions/<session_id>` | Bearer 任务 token | 诊断状态、进度、错误；token 必须对应 URI 中的任务 |
| `POST /api/v1/sessions/retry` | 仅任务 `token` | 重试失败块／回写，保留已完成项 |
| `POST /api/v1/sessions/cancel` | 仅任务 `token` | 幂等停止对应任务，不删除云盘资料 |

开始响应至少为 `session_id, session_token, state`。连接 token 用于连接测试和建立 ASR 任务，不能直接结束某个「最新任务」。两部手机共用连接 token 也必须产生独立任务 token。相同 session_id 配不同路径／身份返回 409。

错误响应采用 `code/message/retryable`，脱敏后提供可读原因；401／403 不自动重试，429／5xx 允许有限退避。普通等待文件是正常状态，不能算作请求失败。电脑重启要恢复会话、任务 token、结束标志和处理进度。

## 5. 文件与完成约定

文件名／字段必须与 RecNote [设计第 3 节](openspec/changes/add-remote-compute-mode/design.md) 一致：

```text
<cloud_path>/
  audio/000001.wav, audio/000002.wav, ...
  manifest.json
  final.json
  .transcripts/000001.md, 000002.md, ...
  .transcripts/index.json
  .transcripts/complete.json
```

- `manifest.json`：手机只在音频上传成功后更新，含 `protocol_version, session_id, revision, sample_rate, channels, encoding, chunks`。每块 `sequence, path, start_sample, sample_count, bytes, sha256`，路径相对于任务根。格式 16 kHz／单声道／PCM16，约 30 秒，允许暂停或停止产生短块。
- `final.json`：手机录音停止且全部音频上传后发布，含最终完整块集合、`total_samples, chunk_count`。电脑在结束通知后仍等这个文件以及每个块；零块不误报有文字。
- `.transcripts/index.json`：电脑整块识别且完整回写 Markdown 后更新，公共身份字段加递增版本；块条目包含 `sequence, audio_sha256, status, path, bytes, sha256, error`。原稿路径相对于 `.transcripts/`。手机只把完整大块当一个原稿段，不需要内部句级时间。
- `.transcripts/complete.json`：电脑全量回写后发布，含 `protocol_version, session_id, final_manifest_sha256` 与全部块输入／原稿指纹。只有它与全部原稿都能验证，手机才标记转写完成。

清单／索引上限 2 MiB，单块原稿 128 KiB。数据校验按实际 UTF-8／文件字节 SHA-256，手机上传清单时保留用于核对的确切字节，不依赖两端重新序列化 JSON 的顺序。半写 JSON、旧索引、文件暂时 404 或校验不完整先等待／重试，不能发布完成；禁止任务路径穿越和任意外部 URL。

仅在本机使用临时文件并可靠封存，不假定网盘支持原子 MOVE。无需每个语句建文件。静音块原稿正文可为空，但仍须生成可核验文件并标记整块识别完成。

## 6. V2N 实现清单

- [ ] 新增 ASR `v2n server` 或独立等价服务入口，直接复用识别函数；保留原 `v2n START-END [TITLE]` 行为，验证旧 CLI 用法不变。
- [ ] 新增 ASR 专用 `config.yaml` 模板与配置加载／校验路径；使用该配置验证服务启动、接收、识别和回写。
- [ ] 实现连接鉴权、独立任务 token、开始／结束／诊断／重试／取消接口与状态持久化；合成测试覆盖双手机、重复开始、错误 token 和电脑重启。
- [ ] 实现电脑 OpenList 的滚动清单轮询、完整下载、WAV 样本／字节／SHA 核验和最终收尾；测试尾块晚于结束、空目录、旧清单、短块、零块及权限失败。
- [ ] 服务直接复用 `transcribe` 的内部按句切分，持久模型实例并使用有界识别并发；测试手机提供的大切片被完整处理，禁止把每个手机块交给 CLI 范围解析重新发现其他文件。
- [ ] 按 session 隔离本地音频工作目录和 `TRANSCRIPT_PATH`，避免同名 `000001.wav` 跨手机覆盖；测试两会话相同编号不会串资料。
- [ ] 实现整块 Markdown 回写和索引／完成标记，分离 ASR 成功与云盘回写成功；测试回写失败只补写、没有完成标记之前手机不能误判。
- [ ] 保护 ASR 服务凭据和状态，日志不输出 token 或 OpenList 密码；配置加载错误也须脱敏，用合成凭据测试所有日志级别。
- [ ] 记录长录音的识别积压、云盘延迟及失败恢复限制；输出明确错误且保留已验证音频／原稿，不静默丢弃资料。
- [ ] 提供协议 v1 合成夹具和可重复模拟云盘／API 测试，与 RecNote 共同核对文件名、路径基准、状态、字段与完整性边界。

## 7. 双仓库联调验收

- [ ] 电脑仅用 ASR 服务配置启动，两端 WebDAV 根对应同一真实文件夹，使用合成 probe 验证；记录真实上传／下载链路，确认音频本体不走电脑公开 cloudflared 接口。
- [ ] 手机发送大块，电脑内部按句处理，原稿只在整块完成后出现在 start 路径 `.transcripts/`；进入详情与刷新能取回且无重复。
- [ ] 结束通知先到、最后块迟到、手机断网、电脑重启和回写失败都能恢复；最终块集合完整才完成。
- [ ] 两台手机同时录音互不影响，结束包体始终只有各自任务 token，不依赖全局最近任务。
- [ ] 用目标手机长录音对照记录温升、耗电和后台稳定性；分别记录协议测试、CI／模拟器验证与人工真机验收，不能互相代替。
