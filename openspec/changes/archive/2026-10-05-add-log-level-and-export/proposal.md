# Proposal

## Why

应用目前完全没有日志代码，用户遇到录音、下载、云端整理等问题时无任何现场信息可提供，只能靠复述操作描述，定位困难。需要一个可由用户控制的日志系统：平时不记录或只记录安全信息，抓问题时可全量记录并一键导出给开发者。

## What Changes

- 设置页最下方新增「日志」区块：日志级别选择（`None` / `Info` / `Debug` 三档）与「导出日志」按钮。
- 三档语义：
  - `None`（默认）：关闭日志，不记录任何内容；
  - `Info`：开启日志，隐私信息（API Key、密钥材料、Authorization 头等）MUST NOT 记录；
  - `Debug`：全盘记录（含请求/响应等细节，隐私信息仍按 Info 同规则脱敏或按档位明确允许的最小集记录），用于抓问题，优先保证信息完整。
- 新增应用内日志组件：统一的分级日志写入（带时间戳、级别、标签），持久化到应用私有存储的 `.log` 文件，随级别变化与启动时生效，切换级别立即生效并持久化。
- 「导出日志」把当前日志文件（`.log`）通过系统分享面板（`ACTION_SEND` + `FileProvider`）分享出去，复用现有导出分享路径的模式。
- 「日志」区块显示当前日志占用（如「当前日志占用： xxxKB」）并提供「清理日志」按钮，一键删除全部已记录日志，占用显示随之归零。
- 对全部现有代码添加日志覆盖：录音服务、ASR 进程与绑定、模型下载、云端笔记整理、导出/打印、设置与恢复流程等关键路径均接入日志调用（生命周期、状态迁移、错误与异常）。

## Capabilities

### New Capabilities
- `app-logging`: 应用日志的级别控制（None/Info/Debug）、隐私脱敏规则、日志文件持久化与 `.log` 导出分享行为。

### Modified Capabilities

（无。`model-download` 等现有能力的行为不因日志接入而改变。）

## Impact

- 新增代码：日志组件（如 `logging/` 包：级别枚举、日志写入器、脱敏工具）、设置页「日志」区块 UI、日志导出分享逻辑。
- 修改代码：新增日志级别持久化（轻量存储，供多进程共享）；[`SettingsScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/SettingsScreen.kt) 增加日志区块（级别选择、占用显示、清理、导出）；[`LectureApp.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/LectureApp.kt) 初始化日志组件；[`RecordingService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/recording/RecordingService.kt)、[`AsrService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/asr/AsrService.kt)、[`AsrClient.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/asr/AsrClient.kt)、[`ModelDownloadService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/models/ModelDownloadService.kt)、[`NotesService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/cloud/NotesService.kt)、[`CloudClient.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/cloud/CloudClient.kt)、[`Exporter.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/export/Exporter.kt)、[`WavFile.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/recording/WavFile.kt)、[`NotesRenderer.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/export/NotesRenderer.kt)、[`MainActivity.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/MainActivity.kt)、[`DetailScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/DetailScreen.kt)、[`CameraScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CameraScreen.kt) 等接入日志调用。
- ASR 独立进程（`:asr`）MUST 单独初始化日志（该进程不建 `AppGraph`），日志写入须跨进程安全（追加写同一文件或分文件合并）。
- 导出走 [`file_paths.xml`](app/src/main/res/xml/file_paths.xml) 已有的 `FileProvider`（`${packageName}.files`），无需新增 provider。
- 依赖：不引入第三方日志库，基于 `android.util.Log` + 自研文件写入，避免新增依赖。
- README：属用户可见行为变化（新增设置项与导出能力），按开发规矩需要在 README 中简要通知。
