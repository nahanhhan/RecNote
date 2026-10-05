# Tasks

## 1. 日志纯逻辑与单测（core）

- [x] 1.1 在 [`core/`](core) 增加日志级别模型（None/Info/Debug）与级别过滤纯函数，验证：新增 `:core:test` 单测覆盖三档过滤行为（None 全拒、Info 收关键事件、Debug 全收）
- [x] 1.2 在 [`core/`](core) 增加凭据脱敏掩码纯函数（`key=`/`token=`/`Bearer …`/`sk-…` 等模式）与日志行格式化（时间戳、级别、来源进程前缀），验证：`:core:test` 单测覆盖 Info 行不出现 API Key 原文、Debug 行保留原文、行格式符合导出要求
- [x] 1.3 在 [`core/`](core) 增加日志容量截断纯逻辑（超上限保留最近内容），验证：`:core:test` 单测覆盖截断后总量回落且最近条目保留
- [x] 1.4 端侧自检：在 [`scripts/tests`](../../../scripts/tests) 执行 `uv run python -m checks` 通过；CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过作为本批验收

## 2. App 端日志运行时与初始化

- [x] 2.1 新增 `logging/` 包写入器：单线程队列异步写 `files/log/<进程>.log`、同时输出 logcat、级别 None 不输出、超上限触发截断、进程退出/服务销毁 flush，验证：CI 编译通过且接入点调用不阻塞（`:app:assembleDebug` 成功）
- [x] 2.2 实现日志级别持久化单行文件（临时文件 + rename 原子写）与进程内 TTL（约 2s）缓存读取，验证：CI 通过；行为按 spec「切换级别立即生效并持久化」在真机清单中覆盖
- [x] 2.3 [`LectureApp.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/LectureApp.kt) 两进程初始化 Logger（`:asr` 进程也初始化，且保持不建 `AppGraph` 的既有约束），验证：CI 通过，`:asr` 进程写 `files/log/asr.log`
- [x] 2.4 端侧自检 `uv run python -m checks` 通过；CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过作为本批验收

## 3. 设置页日志区块与用户文档

- [x] 3.1 [`SettingsScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/SettingsScreen.kt) 最下方新增「日志」区块：None/Info/Debug 三档选择（默认 None、持久化、立即生效），Debug 档附「会记录敏感信息」提示文案，验证：CI 通过
- [x] 3.2 「日志」区块显示「当前日志占用： xxxKB」（`files/log/*.log` 字节数换算，清理/导出后刷新）与「清理日志」按钮（清空全部日志、占用归零、可继续记录），验证：CI 通过
- [x] 3.3 「导出日志」按钮：归并 `files/log/*.log` 为 `cacheDir/exports/lecture-log_<时间戳>.log`，经 [`file_paths.xml`](app/src/main/res/xml/file_paths.xml) 的 `FileProvider` + `ACTION_SEND`（`text/plain`）唤起系统分享；无内容时仅提示不唤起分享，验证：CI 通过
- [x] 3.4 [`README.md`](README.md) 增补日志级别、导出与清理说明；[`verification/DEVICE_CHECKLIST.md`](verification/DEVICE_CHECKLIST.md) 增加日志级别切换、占用显示、清理、导出分享的真机核对项，验证：`uv run python -m checks` 通过且 README 变更仅涉及用户可见行为
- [x] 3.5 端侧自检 `uv run python -m checks` 通过；CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过作为本批验收

## 4. 日志覆盖：录音、ASR、模型下载

- [x] 4.1 [`RecordingService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/recording/RecordingService.kt)、[`WavFile.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/recording/WavFile.kt)、[`MainActivity.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/MainActivity.kt) 接入生命周期、状态迁移（开始/暂停/恢复/结束、分段完成）与错误日志，验证：CI 通过
- [x] 4.2 [`AsrService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/asr/AsrService.kt)、[`AsrClient.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/asr/AsrClient.kt) 接入绑定、识别事件与错误日志（含来源进程可区分），验证：CI 通过
- [x] 4.3 [`ModelDownloadService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/models/ModelDownloadService.kt) 接入下载各阶段、HTTP 状态与失败原因日志，验证：CI 通过
- [x] 4.4 端侧自检 `uv run python -m checks` 通过；CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过作为本批验收

## 5. 日志覆盖：云端整理、导出打印与 UI

- [x] 5.1 [`NotesService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/cloud/NotesService.kt)、[`CloudClient.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/cloud/CloudClient.kt) 接入整理各阶段与错误日志；Debug 级别记录完整请求/响应正文，Info 级别仅非敏感元数据（API Key 不落盘），验证：CI 通过
- [x] 5.2 [`Exporter.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/export/Exporter.kt)、[`NotesRenderer.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/export/NotesRenderer.kt)、[`DetailScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/DetailScreen.kt)、[`CameraScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CameraScreen.kt) 接入导出/打印/拍照关键事件与错误日志，验证：CI 通过
- [x] 5.3 端侧自检 `uv run python -m checks` 通过；CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过作为本批验收

## 6. 集成核对

- [x] 6.1 `openspec validate add-log-level-and-export` 通过，spec 场景与实现行为逐条对照无遗漏
- [x] 6.2 全量 CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过（仅表述为工程验证通过，真机验收按本 change tasks 对应人工验收项人工勾选执行）
