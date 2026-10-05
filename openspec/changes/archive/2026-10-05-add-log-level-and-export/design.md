# Design

## Context

应用为 Kotlin/Compose 单模块 Android 应用（[`app/`](app)），核心纯逻辑在 [`core/`](core) 模块（JVM 单测）。现状无任何日志代码（`android.util.Log`、Timber 均未使用）。日志需要覆盖主进程（录音、下载、云端整理、UI）与独立 `:asr` 进程（[`AsrService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/asr/AsrService.kt)），[`LectureApp.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/LectureApp.kt) 中 `:asr` 进程不建 [`AppGraph`](app/src/main/java/io/github/nahanhhan/lecturerecording/LectureApp.kt)。导出分享已有成熟模式可复用：[`DetailScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/DetailScreen.kt) 中 `FileProvider`（`${packageName}.files`，见 [`file_paths.xml`](app/src/main/res/xml/file_paths.xml)）+ `ACTION_SEND`。验证由 CI 承担（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）。动机见 proposal.md - Why。

## Goals / Non-Goals

**Goals:**
- 三档日志级别（None/Info/Debug）行为与脱敏边界清晰、可测试。
- 日志写入不阻塞录音/识别/下载关键线程。
- 主进程与 `:asr` 进程的日志在一次导出中都可见，级别切换两进程近实时一致。
- 导出走系统分享，产物为单个 `.log` 文件。

**Non-Goals:**
- 不做远程日志上报/崩溃收集。
- 不做应用内日志查看器 UI（仅导出）。
- 不抓取 logcat 或其他应用日志。
- 不引入第三方日志/依赖库；[`core/`](core) 模块保持纯 JVM，不接入 Android 日志。

## Decisions

1. **自研轻量 Logger（`logging/` 包）而非 Timber/SLF4J**：项目零日志依赖，需求仅分级 + 文件落盘 + 脱敏，自研约百余行可控；引入库会带来混淆、依赖树与 CI 变量。备选 Timber：需新增依赖且文件落盘仍要自写，收益低。

2. **日志级别持久化为单行文件（如 `files/log/level`），不放 SharedPreferences**：级别需被 `:asr` 进程读取，而 SharedPreferences 多进程缓存不可靠（`MODE_MULTI_PROCESS` 已废弃）。级别文件原子写（临时文件 + rename），各进程内存缓存 + 短 TTL（约 2s）刷新，切换近实时生效。备选 SharedPreferences + `MODE_MULTI_PROCESS`：已废弃，不采用。

3. **双通道输出：文件为主、logcat 为辅**：所有级别非 None 的条目同时写 `android.util.Log`（便于 `adb logcat` 调试），文件是导出来源。级别为 None 时两通道均不输出。

4. **每进程独立日志文件，导出时按时间戳归并**：主进程写 `files/log/main.log`，`:asr` 进程写 `files/log/asr.log`，避免跨进程文件锁争用；导出时按行时间戳归并为单个 `lecture-log_<时间戳>.log`（放 `cacheDir/exports/`），每行前缀标注来源进程。备选单文件 + `FileLock`：锁竞争与半行写风险高，不采用。

5. **脱敏在写入端集中处理，调用点双保险**：Info 级别下，Logger 对每行做凭据模式掩码（`key=`/`token=`/`Bearer …`/`sk-…` 等正则替换为 `***`），且调用点在 Info 语义下不传入凭据值（只传存在性、长度、模型名等元数据）。Debug 级别不做掩码，全量记录（与需求「全盘记录，抓问题优先」一致）。备选仅调用点自律：遗漏风险高；备选两级都掩码：与 Debug 语义冲突。

6. **写入经单线程队列**：`AppLog` 内部用 Channel/单线程 executor 串行写盘，调用点非阻塞；进程退出/服务 `onDestroy` 时 flush。日志超上限（5 MB/文件）时截断保留最近内容（读回重写或滚动双文件取新）。

7. **导出在设置页发起**：「导出日志」收集 `files/log/*.log` 归并 → `cacheDir/exports/lecture-log_<时间戳>.log` → `FileProvider.getUriForFile(activity, "${packageName}.files", file)` → `ACTION_SEND` `text/plain`（同 [`DetailScreen.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/DetailScreen.kt:172) 模式）。无内容时仅提示，不唤起分享。

8. **占用显示与清理**：「日志」区块显示「当前日志占用： xxxKB」，数值取 `files/log/*.log` 文件字节数之和换算 KB（打开设置页时计算一次，清理/导出后刷新）；「清理日志」清空 `files/log/` 下全部日志文件内容（Logger 保持可继续追加写），占用显示归零。清理前不需确认（日志可再生，无用户数据）。

9. **覆盖点选择**：服务生命周期（`onCreate`/`onStartCommand`/`onDestroy`）、状态迁移（录音开始/暂停/恢复/结束、下载各阶段、整理各阶段）、错误捕获（`catch`/失败回调）、跨进程绑定事件处加 `AppLog.i/d/e`；Debug 级别额外记录请求/响应正文（如 [`CloudClient.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/cloud/CloudClient.kt) 完整报文、[`NotesService.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/cloud/NotesService.kt) 分批结果摘要）。

10. **日志级别初始化入口**：[`LectureApp.onCreate`](app/src/main/java/io/github/nahanhhan/lecturerecording/LectureApp.kt) 无条件初始化 Logger（两进程共用），`:asr` 分支不建 `AppGraph` 的既有约束不变。

## Risks / Trade-offs

- [Debug 级别可能记录 API Key 与转写内容] → 日志仅存应用私有目录、导出为用户主动行为；设置页 Debug 档文案标注「会记录敏感信息，抓问题后请切回 Info」，分享面板标题注明「日志可能含敏感信息」。
- [日志写盘影响录音实时性] → 单线程异步队列 + 有界缓冲，丢弃策略宁丢日志不阻塞录音。
- [日志无界增长占满存储] → 5 MB 上限截断最旧；级别 None 不写入。
- [多进程级别短暂不一致] → TTL 缓存约 2s，切换后 2s 内两进程一致；级别文件原子写避免读到半行。
- [截断重写耗时] → 仅在超限时发生，于 IO 线程执行，可接受。
- [Info 脱敏正则漏网] → 调用点不传凭据值双保险；单元测试覆盖掩码规则（放 [`core/`](core) 或 app 本地测试均可，掩码函数保持纯函数便于 JVM 测试）。

## Migration Plan

无存量数据迁移。默认级别 None，行为与现状（不记录）一致，升级即生效。回滚：移除日志区块与 Logger 调用即可，无数据格式牵连（日志文件可直接删除）。

## Open Questions

（无。）
