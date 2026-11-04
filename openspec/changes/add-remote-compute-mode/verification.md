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

隔离模拟器运行仍在进行。只有源码编译通过的运行场景继续保留待验收状态，测试 APK 的生成不代表真实手机、真实网盘或实际电脑对端验收完成。

新增 22 个测试用例：协议 6 个、控制客户端 5 个、模拟对端运行测试 8 个、设置/加密/页面 2 个、隔离模拟器完整块/暂停尾块录音 1 个。既有迁移测试同步升级到数据库版本 7。

早前自动审批因用户当时只明确授权本地 Git 记录而拒绝推送；用户随后明确授权上传到 `https://github.com/nahanhhan/RecNote.git`，该限制已解除。实际对端联调与目标手机/真实网盘验收仍留待后续。

## RecNote-Sync 目标契约更新（2026-11-04）

根据用户提供的 `RecNote-Sync.md` 同步 15 项定稿差异，更新提案、设计、相关 spec、任务、全局 context 及两份协议／交接说明。本次只改文档，未修改 Kotlin／测试实现，未重新构建 APK，也未将新增适配任务勾选为完成。

先前提交 `a8e53ee` 的工程通过及 APK 签名校验只证明旧契约版本的构建结果。新目标已改为云盘根相对路径、无 bytes 的音频条目、completed 接口状态、带回执的双向探测、按 retryable 决策，以及含已完成任务的持久取消清理；旧 APK 不因本次文档更新自动兼容。

新增第 7 组 6 项适配任务，按对端样例、数据格式、连接探测、控制错误、取消发件箱及完整回归拆分。对端四份 JSON／字段说明、回执内部字段及体积上限数值未随附件提供，已作为实现前资料依赖登记，未擅自复制或生成替代样例。附件引用的电脑侧设计定稿不等于其服务已运行。

本次文档更新经官方 `openspec validate add-remote-compute-mode --strict` 校验通过，统一端侧 `uv run python -m checks` 8 项通过；这些结果不等于新契约实现或联调已通过。
