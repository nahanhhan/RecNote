# Proposal

## Why

v1 的 RecNote 只是跑通了录音、转写、整理等组件，交互仍是手动式：用户必须自己打开应用、点「开始课堂录音」、手填标题。RecNote 预期的交互是「日程驱动」：用户从桌面进入应用时，应用自己判断「现在是不是某节课/某场会议」，是就直接开录并用日程名命名。v2 重构的第一个核心功能就是为此打底的 ICS 日程解析，以及基于日程的自动开录与自动命名；同时需要把「停止录制」抽象成可扩展入口，为日后的日程下课、运动传感器、关键词触发预留代码口。

## What Changes

- 新增 ICS 日程接入：支持本地导入 `.ics` 文件，以及订阅 `.ics` 网络链接（`webcal://`/`https://`，定时刷新）。解析覆盖课表常见写法：`DTSTART`/`DTEND`/`DURATION`、`TZID`/UTC/浮动时间、全天事件、`RRULE`（含 `UNTIL`/`COUNT`/`BYDAY`/`INTERVAL`）、`EXDATE`、`RECURRENCE-ID` 覆盖。
- 新增「自动开录阈值」设置：一个带两个拉杆的区间滑块，范围 +5 到 -10 分钟（以日程开始时刻为 0，正数表示早于开始，负数表示晚于开始），默认 -3 ～ +3；两拉杆之间即自动开录区间。
- 新增启动即判定：从桌面进入应用时，若当前时刻落在某日程开始时刻的自动开录区间内，立即开始录音，课堂标题取该日程名，课程名同步取日程名。判定只使用本机已缓存日程，不等待网络。
- 新增同一日程内的自动分段命名：日程进行中若录音被中断又再次开始，不再弹出标题输入，按 `日程名-1`、`日程名-2` 自动命名。
- 新增「停止触发器」抽象：把现有「停止」收口为统一的停止请求入口，当前仅实现「手动」触发器；为「日程下课时间」「运动传感器」「关键词」预留注册点、挂载策略（运动传感器与关键词须在日程结束时刻之后才开始监听）和「本次改为手动」的开关。**不**实现这三种触发器本身及关键词 DSL。
- 数据库升级到版本 3：课堂新增日程绑定字段，新增日程来源表；旧数据非破坏性迁移。

## Capabilities

### New Capabilities

- `ics-schedule-source`: ICS 日程的导入、订阅、刷新、解析与按时间区间展开出现项（occurrence），及日程来源的本机持久化与失败保留策略。
- `schedule-auto-recording`: 基于日程的自动开录阈值、启动即判定、日程驱动的标题/课程命名与同日程多次录音的 `-x` 命名，录音开始后直接进入录音详情（自动与手动一致），以及相应设置界面。
- `recording-stop-triggers`: 录音停止请求的统一入口与触发器扩展约定（当前仅手动），含触发器挂载时机与「本次改为手动」语义的预留。

### Modified Capabilities

<!-- 现有 openspec/specs 仅有 model-download，本次不改变其要求。 -->

## Impact

- 新增 `core` 纯 Kotlin 逻辑（可由 `:core:test` 验证）：`IcsParser`、重复规则展开、`ScheduleResolver`（按当前时刻和阈值选出日程）、`SessionNaming`、`StopTrigger` 抽象。
- `app` 受影响：`data/Database.kt`（版本 3 迁移，`LessonEntity` 新增字段，新增 `schedule_sources` 表）、`data/SettingsStore.kt`（阈值）、`LectureApp.kt`（`AppGraph` 持有日程仓库）、`MainActivity.kt`（启动即判定、手动开始时跳过标题对话框的条件、开始后导航到录音详情、移除自动开录 Toast）、`recording/RecordingService.kt`（接收日程绑定参数；停止指令改经停止触发器入口）、`ui/SettingsScreen.kt` 及新增日程设置面板；新增日程仓库与定时刷新任务。
- 新增依赖：`androidx.work:work-runtime-ktx`（订阅链接定时刷新）。无新增权限（`INTERNET` 已声明）；订阅仅限 HTTPS，`usesCleartextTraffic` 保持关闭。
- 文档：`docs/ARCHITECTURE.md` 补充日程与停止触发器章节、数据库版本 3；`README.md` 因新增用户可见的自动开录行为而更新。`androidTest` 的 `DatabaseMigrationTest` 需覆盖 2→3 迁移。
- 不在范围内：日程下课/运动传感器/关键词三种停止触发器的实现及关键词 DSL；系统日历（CalendarProvider）读取；云端日历账号授权；后台定时（无需用户进入应用）自动开录。
