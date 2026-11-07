# RecNote 远程识别实施与验证记录

## 范围

2026-11-04 按用户确认，只改 RecNote 客户端，保留现有手机整理与导出流程。实际电脑服务改造、双方联调和人工真机验收后续进行。未修改 Voice2Note 仓库。

开始前已 `fetch origin`，确认当前分支包含 `origin/main`，没有落后提交。工作分支为 `codex/remote-compute-mode`。第一批实现与测试保存在本地提交 `cf398a4`；边界修正与隔离测试保存在 `0c3c3ff`；协议记录提交为 `a8e53ee`。用户随后明确授权推送，现已上传至同名云端分支，保留主分支。

## 代码与测试源码

| 范围 | 本轮落地内容 | 验证源码 |
| --- | --- | --- |
| 模式与配置 | 顶部模式切换、电脑配置、加密 token、记录模式冻结、Room 6→7 非破坏性迁移 | `RemoteSettingsTest`、`DatabaseMigrationTest` |
| 采集与导入 | 远程跳过手机识别与按句切分；完整块/暂停尾块封存；异常 WAV 修复 | `RemoteRecordingTest`、`RemoteModeTest.remoteImportBypassesRecognizerAndCreatesOnlySealedLargeChunks` |
| 上传与控制 | 持久队列、任务 token、仅 token 结束通知、原字节最终清单、配置改变暂停、有限重试、状态与取消 | `RemoteControlClientTest`、`RemoteModeTest` |
| 原稿与整理 | 按详情进入/刷新取回，指纹/任务/路径/大小检查，完整集合才完成，编辑保护与来源版本 | `RemoteProtocolTest`、`RemoteModeTest` |
| 安全自检 | 凭据加密、控制重定向和日志边界 | 统一 checks 的 `remote_safety` |

协议字段、文件名、状态和限制写入 [remote-asr-protocol.md](../../../docs/remote-asr-protocol.md)，与设计和交接文档的音频/原稿约定核对。

## 已实际执行

- 本地统一只读检查 `scripts/tests` 下 `uv run python -m checks`：8 项通过。
- `openspec validate add-remote-compute-mode --strict`：通过。
- `git diff --check`：通过。
- 用户提供的 `http://localhost:5244/`：HTTP 200，服务在线；`PROPFIND /dav/`：HTTP 401，WebDAV 需要登录。只进行了只读可达性检查，没有上传真实录音，也没有完成真实网盘读写测试。
- [CI 工程验证](https://github.com/nahanhhan/RecNote/actions/runs/41778363066/job/126403893862)：提交 `a8e53eeda4a43f1ed43f6a65066199516cfff03f` 的核心/Android 单测、仪器测试源码编译、lint、debug APK 构建通过。源码编译不等同仪器测试运行通过。

## 已交付测试 APK

按用户请求，从上述 CI 下载 `lecture-recording-debug` 产物并核对 ZIP 摘要与 GitHub 元数据一致。安装包为 `.build-cache/apk/recnote-remote-a8e53ee.apk`，94,831,771 字节；APK SHA-256 为 `0b71444080a7f3293916b66b9adad18eaba1ccc445410090acc3ad45131fa247`。

使用 Google 官方 apksig 8.13.2 实际验证 APK v2 签名通过，证书 SHA-256 与仓库 `debug.keystore` 及既有发布包记录一致：`4149018b4ba6e74ff27c12e96d775fe01bbab4b510275509c1a7225a89f81f3e`。

实际读取安装包清单：应用 ID `io.github.nahanhhan.lecturerecording`，版本名仍为 `0.1.9-alpha`、versionCode 8，ABI 为 arm64-v8a，debuggable 为 true。这是包含远程模式的分支测试包，不是旧版发布 APK；覆盖安装会执行 Room 6→7 迁移。用户测试前应备份重要笔记。没有发布新 Release 或覆盖旧版资产。

## 尚待执行

历史提交 `a8e53ee` 的上述 CI 已完成，工程验证与隔离模拟器运行均成功。测试 APK 的生成和模拟器结果不代表真实手机、真实网盘或实际电脑对端验收完成。下文新契约实现单独验证。

新增 22 个测试用例：协议 6 个、控制客户端 5 个、模拟对端运行测试 8 个、设置/加密/页面 2 个、隔离模拟器完整块/暂停尾块录音 1 个。既有迁移测试同步升级到数据库版本 7。

早前自动审批因用户当时只明确授权本地 Git 记录而拒绝推送；用户随后明确授权上传到 `https://github.com/nahanhhan/RecNote.git`，该限制已解除。实际对端联调与目标手机/真实网盘验收仍留待后续。

## RecNote-Sync 目标契约更新（22f84e7，实施前记录）

根据用户提供的 `RecNote-Sync.md` 同步 15 项定稿差异，更新提案、设计、相关 spec、任务、全局 context 及两份协议／交接说明。本次只改文档，未修改 Kotlin／测试实现，未重新构建 APK，也未将新增适配任务勾选为完成。

先前提交 `a8e53ee` 的工程通过及 APK 签名校验只证明旧契约版本的构建结果。新目标已改为云盘根相对路径、无 bytes 的音频条目、completed 接口状态、带回执的双向探测、按 retryable 决策，以及含已完成任务的持久取消清理；旧 APK 不因本次文档更新自动兼容。

新增第 7 组 6 项适配任务，按对端样例、数据格式、连接探测、控制错误、取消发件箱及完整回归拆分。对端四份 JSON／字段说明、回执内部字段及体积上限数值未随附件提供，已作为实现前资料依赖登记，未擅自复制或生成替代样例。附件引用的电脑侧设计定稿不等于其服务已运行。

本次文档更新经官方 `openspec validate add-remote-compute-mode --strict` 校验通过，统一端侧 `uv run python -m checks` 8 项通过；这些结果不等于新契约实现或联调已通过。

## RecNote-Sync 客户端实施（2026-11-04）

已在提交 `ae4835b` 至 `6fa1947` 落实 RecNote 客户端：

| 范围 | 行为与证据 |
| --- | --- |
| 文件字段 | 根相对路径；音频与索引无 bytes；滚动清单不输出最终集合专用空字段；索引仅 completed/failed；完成条目独立类型，不携带 status/error；`RemoteProtocolTest`、`RemoteProtocolFixtureTest` |
| 连接测试 | health 使用 storage_ready；probe 带协议版本，回读并核验预期回执路径与原字节摘要；冷模型仅提示，ok=false 不退避；`RemoteControlClientTest`、`RemoteModeTest` |
| 错误／状态 | 六个接口 state；结构化 retryable 控制有限重试；终态提示新建任务；区分体积、格式、缺文件及回写失败；服务端原始错误信息不回显 |
| 取消恢复 | 独立 Room 取消表与 WorkManager 队列；删除事务先存通知，保留原连接及加密 token；已完成任务同样取消；成功确认前不清除，配置改变暂停，迟到结果不重建录音 |
| 升级兼容 | Room 7→8 暂停旧协议未完成任务，保留已完成缓存；用户确认后复制音频／照片到新任务，保留原记录及不可变 final；`DatabaseMigrationTest`、`RemoteModeTest.legacyTaskIsRebuiltWithoutModifyingItsPublishedFinalManifest` |

最初对照 Voice2Note `e4b958f` 设计发现并修正索引 bytes、完成条目 status、health 字段分歧。提交前复查时，对端更新到 `7dd3e1d3c88fdf25e67954a91ac7e3eedcfc206a` 并发布正式四份 JSON 与字段说明，已原字节复制到 `core/src/test/resources/voice2note-protocol-v1/`，取代先前的三份设计示例。Kotlin 测试核对文件摘要、字段集合、往返及跨文件引用；不运行电脑端 Python。样例内容摘要为示例值，实际文件完整性链由另行合成数据验证。

同提交的 storage.py 明确回执字段为 `protocol_version, probe_path, probe_sha256, asr_ready, timestamp`（UTC 秒精度）；已按字段校验并更新模拟服务及异常测试。模板音频上限为 16 MiB，探测上限 64 KiB。仅只读核对对端代码，没有改动 Voice2Note 源码，也没有执行真实双方联调。

本地统一检查 8 项、官方 OpenSpec 严格校验及 diff 检查通过。正式样例版 `6fa194719f9fe0fd9245701963650e9a3c8474cc` 的 [最终 CI](https://github.com/nahanhhan/RecNote/actions/runs/41792866872) 已全部成功：核心及 Android 单元测试、仪器测试源码编译、lint、APK 构建，以及实际运行 30 个隔离模拟器用例。四份原样例字段／原字节摘要断言与具名回执异常检查均通过。

本轮第 7 组 6/6 项按 RecNote 客户端范围验收完成。整份提案清单为 6/36 项勾选，原有分组的完整验收与第 6 组双方联调／人工真机验收保持各自状态，没有批量代勾；不将客户端模拟服务结果视为实际电脑、真实云盘或目标手机验收。旧 `a8e53ee` APK 不包含本节适配；新 APK 来自上述 CI 的 `lecture-recording-debug` 产物。

## 新契约测试 APK 交付

按用户请求，下载 `6fa1947` 的 CI 产物 `13304616259` 并核对 ZIP SHA-256 与 GitHub 元数据相同：`7347227a41b6b4497ee101f4b910d03dd45292f4f113865c5237f84d5ef32b36`。安装包保存为 `.build-cache/apk/recnote-remote-6fa1947.apk`，94,930,075 字节，APK SHA-256 为 `666deefdc02e364b823564be18f24bd413654205350b8e6386ab5ff381c09f84`。

使用 Google apksig 8.13.2 实际验证 v2 签名通过；另从当前仓库 `debug.keystore` 导出证书，确认与 APK 签名证书 SHA-256 相同：`4149018b4ba6e74ff27c12e96d775fe01bbab4b510275509c1a7225a89f81f3e`。附带本地 JSON 校验记录，未更换签名密钥，未发布 Release。

## 连接测试诊断修复（2026-11-05）

原实现将 WebDAV 的不同网络异常合并成通用“检查网络、地址和 HTTPS 证书”，测试过程也不进入导出日志。`e4c369c` 新增安全网络分类与失败阶段，区分连接被拒绝、超时、DNS、TLS／证书、系统权限及 HTTP 策略；网络暂时失败保留有限重试，证书和权限错误转人工。Info/Debug 日志仅记录协议、端口、目标是否本机、测试阶段、固定分类及状态码，不保存地址、文件夹名、原始异常或服务端正文。电脑控制接口的非 JSON 502/503/504 页面改为服务启动／转发检查提示，重试仍服从有效协议错误体。

新增网络拒绝、读取超时、错误分类、诊断日志脱敏及网关错误页面单元测试，并新增隔离界面测试确认“检查目录：连接被拒绝”实际可见。统一检查 8 项及 OpenSpec 严格校验通过；[诊断版 CI](https://github.com/nahanhhan/RecNote/actions/runs/41953041799) 已全部通过，含编译、单测、lint、APK 构建及实际执行 31 个隔离模拟器用例。8.1 的客户端诊断修复验收完成。真实手机连接原因仍需结合实际服务端口、外部浏览器及新诊断结果确认，没有修改手机 OpenList 或电脑识别服务。

诊断 APK 为 `.build-cache/apk/recnote-webdav-diagnostics-e4c369c.apk`，94,946,459 字节，SHA-256 `2745f530f95656132762a3c2f3936f86f395d256ae17c08cce9ca8d52c96e57b`。GitHub 产物 `13382546359` 的 ZIP SHA-256 已与元数据核对一致：`caf83a1e24fdbb6b4b2f90cedf52a5cd94e30e45380690cf0f305e620252ba5c`。apksig 实际验证 v2 签名通过，签名证书与上文仓库证书一致；附本地 JSON 校验记录。

## 远程日志验证（2026-11-07）

提交 `927a998` 将连接探测、音频和清单上传、电脑任务状态与原稿回收写入 `RemoteTrace`。隔离测试实际执行电脑读取失败、电脑确认写入后手机读取 404，以及一轮音频上传与原稿回收，验证日志编号关联与凭据、地址、路径和正文脱敏。[CI](https://github.com/nahanhhan/RecNote/actions/runs/42340357434) 的工程检查和 32 个模拟器用例通过。

同签名 APK 为 `.build-cache/apk/recnote-remote-trace-927a998.apk`，产物 ID `13561865460`，APK SHA-256 为 `aa15862dce2b2c0a1f6e98f8b24b9b7f385eb904ba3a23c56168b8512e196e90`。下载 ZIP 与 GitHub 摘要一致，APK v2 签名证书与仓库一致。真实手机上两端目录与缓存造成的文件可见性仍需依据复测日志确认。
