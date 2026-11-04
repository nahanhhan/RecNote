# RecNote 远程识别实施与验证记录

## 范围

2026-11-04 按用户确认，只改 RecNote 客户端，保留现有手机整理与导出流程。实际电脑服务改造、双方联调和人工真机验收后续进行。未修改 Voice2Note 仓库。

开始前已 `fetch origin`，确认当前分支包含 `origin/main`，没有落后提交。工作分支为 `codex/remote-compute-mode`。第一批实现与测试保存在本地提交 `cf398a4`；边界修正与隔离测试保存在 `0c3c3ff`；本记录和协议随独立文档提交保存。没有推送或合并主分支。

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

## 尚待执行

Kotlin 编译、单元测试、lint、APK 构建及隔离模拟器运行均待 CI。测试源码尚不能证明这些行为已通过；当前不标记任务验收完成，不发布 APK。

新增 22 个测试用例：协议 6 个、控制客户端 5 个、模拟对端运行测试 8 个、设置/加密/页面 2 个、隔离模拟器完整块/暂停尾块录音 1 个。既有迁移测试同步升级到数据库版本 7。

自动审批拒绝了测试分支推送，理由为用户目前明确授权本地 Git 记录，将代码发送至 GitHub 需要进一步确认。因此只保存本地提交；获得测试分支推送授权后可运行 `.github/workflows/android.yml`。实际对端联调与目标手机/真实网盘验收仍留待后续。
