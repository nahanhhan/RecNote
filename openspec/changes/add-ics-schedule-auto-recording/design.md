# Design

## Context

见 proposal.md - Why。当前代码基线（已阅读）：

- 录音只有一个入口：`MainActivity.beginRecording(intent)` 启动 `RecordingService`（`START`），标题和课程由首页的「新建课堂」对话框经 Intent extra 传入；服务内 `runRecording` 创建 `LessonEntity`。停止来自通知栏的 `STOP` action，服务内部以 `stopping` 标志结束采集循环并收尾。
- 数据层为 Room `lectures.db` 版本 2（`MIGRATION_1_2` 已存在），设置存于 `SettingsStore`（SharedPreferences）。
- `core` 模块是无 Android 依赖的 JVM 库，由 `:core:test` 在 CI 验证；`app` 里没有 WorkManager。`AndroidManifest` 已有 `INTERNET`，且 `usesCleartextTraffic=false`。
- 项目约束：Kotlin 侧验证由 CI 承担；任务按可独立验证的小批次组织。

## Goals / Non-Goals

**Goals:**

- 把「ICS 文本 → 出现项 → 当前该录哪节课 → 标题」做成 `core` 内可纯 JVM 测试的函数，Android 侧只负责取数、存储和触发。
- 进入应用时的自动开录判定只读本机缓存，快速且确定。
- 把「停止」收口为带原因的统一入口，并约定触发器挂载时机，使后续三种触发器只是新增实现。

**Non-Goals:**

- 不实现日程下课/运动传感器/关键词触发器及关键词 DSL（逗号 AND、分号 OR、中英文标点）；不实现「置信度不高时的通知提醒」。DSL 与运动检测届时另立 change，本设计只保证它们能挂上来。
- 不读系统日历、不做账号授权的日历源（Google/Outlook OAuth）。
- 不做用户不进入应用时的后台自动开录（Android 14+ 从后台启动麦克风前台服务受限，且会引入隐私与权限复杂度）。
- 不新增 ICS 编辑或日程列表主界面，仅在设置页提供来源管理和近期日程预览。

## Decisions

### D1：自写最小 ICS 解析器放在 `core`，不引入 ical4j/biweekly

课表类 ICS 用到的特性集合小而固定（见 spec）。ical4j/biweekly 体积大，依赖在 Android 上需处理 R8 与时区数据，且 `core` 目前依赖极少。自写解析器可在 `:core:test` 内用夹具全面验证，并按「不支持就降级为首次出现」的策略保证健壮。
替代方案：ical4j（功能全，但体积、混淆、`TZID` 依赖其自带时区库，风险高）；Android `CalendarContract`（需要日历权限且不是 ICS 文件）。

解析分三层：①行展开与属性解析（折行、转义、参数）；②`VEVENT` 抽取（`SUMMARY`、`DTSTART`、`DTEND/DURATION`、`RRULE`、`EXDATE`、`RECURRENCE-ID`、`UID`）；③`occurrences(from, to)` 按区间展开。时间用 `java.time`：`TZID` 先 `ZoneId.of`，失败回落手机时区（并映射常见 Windows 时区名如 `China Standard Time`）；浮动时间用手机当前时区；`VALUE=DATE` 标记为全天，解析保留但在判定层过滤。RRULE 仅支持 `FREQ/INTERVAL/COUNT/UNTIL/BYDAY`，`BYDAY` 的序数前缀（如 `1MO`）和 `BYSETPOS` 等视为不支持。展开时先在本地时区的墙钟时间上迭代再转绝对时刻，以正确跨越夏令时。展开区间有上限（如一次最多 400 条）防止病态 `COUNT`。

### D2：出现项以「来源 id + UID + 本地原始开始时刻」为稳定键

自动开录要判定「该出现项此前是否已有课堂」，因此需要稳定键 `occurrenceKey`。`RECURRENCE-ID` 覆盖用被覆盖的原始时刻作键的一部分，使调课后仍是同一次出现；订阅刷新不会改变未变化事件的键。`LessonEntity` 新增 `scheduleKey`（可空，默认空串）与 `scheduleTitle`（该日程名，用于 `-x` 命名的稳定前缀，不受用户后改标题影响）。无日程的课堂二者为空。

### D3：日程来源——Room 表保存元数据，ICS 原文落盘

新表 `schedule_sources(id, kind[file|url], name, url, lastSuccessAt, lastError, etag)`；原文保存到 `files/schedules/<id>.ics`，刷新时先下载到临时文件，解析成功才原子替换，失败则保留旧文件并更新 `lastError`，满足「失败保留最近成功」。解析结果做进程内缓存（按文件 mtime 失效），查询时展开所需区间。不把每个事件入库：事件量小，重复规则展开本就在读时进行，入库反而要处理增量同步与过期。
替代方案：SettingsStore 的 JSON 列表（无需迁移，但订阅状态和多来源增删更适合关系表，且与本变更需要的 2→3 迁移合并成本几乎为零）。

数据库版本升到 3：`ALTER TABLE lessons ADD COLUMN scheduleKey TEXT NOT NULL DEFAULT ''`、`scheduleTitle` 同理，并 `CREATE TABLE schedule_sources`。需要同步 `docs/ARCHITECTURE.md` 的对象清单描述与 `DatabaseMigrationTest`。

### D4：订阅刷新——WorkManager 周期任务 + 启动时机会性刷新

引入 `androidx.work:work-runtime-ktx`，注册 12 小时周期、需要网络的唯一任务；应用进入时若某订阅距上次成功超过 6 小时，则在 `AppGraph.scope` 里异步刷新一次；设置页提供手动刷新。仅接受 `https`（`webcal` 重写为 `https`），不跟随跳转到 `http`；响应体大小设上限（如 5 MB）与超时；使用 `If-None-Match`（保存 `etag`）减少流量。自动开录判定不 `await` 任何刷新，只读当前缓存。
替代方案：自写 `AlarmManager`（需处理重启恢复与省电策略，WorkManager 已封装）；仅启动时刷新（不满足「定时刷新」）。

### D5：核心判定函数 `ScheduleResolver`（纯函数）

```
resolveAutoStart(now, occurrences, window: AutoStartWindow, existingKeys): Occurrence?
resolveActive(now, occurrences, window): Occurrence?   // 用于手动开始/命名
```

- 阈值滑块值 `[lower, upper]`，取值范围 `lower∈[-10,5]`、`upper∈[-10,5]`，约束 `lower ≤ upper`，默认 `[-3, +3]`；偏移 `offset = (start - now)` 的分钟（带小数，按毫秒比较）。自动开录窗口：`lower ≤ offset ≤ upper`，即开始前至多 `upper` 分钟、开始后至多 `-lower` 分钟（`lower` 为正数时表示「至少提前 `lower` 分钟」之后才开始触发，作为区间滑块的自然语义保留）。边界闭区间。
- 自动开录只选「窗口命中 且 无关联课堂」的候选；多个候选取 `|start-now|` 最小，相同取开始较早者。全天事件在候选前过滤。
- `resolveActive`（供手动开始用）：`start - upper ≤ now < end` 的候选，同样的选择规则。若该出现项已有课堂，则走 `-x` 命名；没有则以日程名命名；两种情形都跳过标题对话框。无候选则维持 v1 对话框。
  注意手动开始使用窗口起点 `start - upper`，与自动开录同口径，使用户只需理解一个阈值。

放进 `core` 的数据类型：`Occurrence(sourceId, uid, key, title, startMs, endMs, allDay)`、`AutoStartWindow(lowerMin, upperMin)`。

### D6：命名函数 `SessionNaming`

输入：日程名与该 `scheduleKey` 下现有课堂标题集合。无现有课堂 → 日程名；否则 → `日程名-(最大已存在后缀+1)`，后缀从 1 起；非 `日程名-<整数>` 形态的标题不计入后缀，但若恰好与候选重名则继续递增。选用「最大后缀+1」而非「课堂数」或「最小空位」，保证删除课堂后不会复用已存在标题。用户把课堂标题改名不影响后续编号来源（以 `scheduleTitle` 为前缀、以现有标题集合为避让依据）。

### D7：启动即判定的触发位置与幂等

`MainActivity.onCreate` 在 `savedInstanceState == null` 且 Intent 为 `ACTION_MAIN`+`CATEGORY_LAUNCHER` 时执行一次判定；`onNewIntent`（后台应用被启动器再次唤起）同条件再执行。旋转等重建 `savedInstanceState != null` 不触发。判定在 `graph.initialized.await()` 之后（避免与冷启动恢复竞争：恢复会把旧的 `recording` 课堂置为 `interrupted`），用 `lessonOperations` 互斥锁保护「读取已有课堂 → 决定 → 创建」，且在锁内再次检查 `recording.lessonId == null && importing.lessonId == null`。命中后构造与手动开始相同的 `START` Intent，额外携带 `title`、`course`、`schedule_key`、`schedule_title`，经现有 `beginRecording` 走权限流程，因此无需新增服务路径。首页同时显示「已根据日程《X》自动开始录音」的一次性提示（Toast + 当前录音卡片文案）。

不在判定里考虑用户「刚手动停止」的情况：由「该出现项已有课堂则不再自动开录」天然避免停止后重进应用又自动开录；此后再录由用户手动点击，并按 D5/D6 自动命名。该假设已写入 spec 的「此前没有关联课堂」条件。

### D8：停止触发器抽象（仅预留）

`core` 新增：

```
enum class StopReason { MANUAL, SCHEDULE_END, MOTION, KEYWORD }
interface StopTrigger { val id: String; val arming: ArmingPolicy; fun start(ctx: SessionContext, requestStop: (StopReason)->Unit); fun stop() }
sealed interface ArmingPolicy { Immediately; AfterScheduleEnd; Custom }
class StopTriggerRegistry(...)   // 管理挂载、switchToManual()、isManualOnly
```

`RecordingService` 持有注册表；`STOP` action 与页面停止均调用 `requestStop(StopReason.MANUAL)`，由其置 `stopping`（幂等）。当前仅注册 `ManualStopTrigger`（`Immediately`，无监听）。注册表提供时钟驱动的 `tick(now)`：对 `AfterScheduleEnd` 的触发器在 `now ≥ occurrence.endMs` 时调用 `start`（每次录音会话至多一次），`switchToManual()` 之后永不再启动并停止已启动者，会话结束即复位。服务内用已有协程调度这个 tick（每秒或下一个相关时刻），未注册自动触发器时零开销（无注册则不调度）。
关键词 DSL 与运动传感器将来作为新的 `StopTrigger` 实现接入，`SessionContext` 已携带 `Occurrence?`（含结束时刻）供其使用；无日程的录音，触发器自声明策略。

替代方案：现在就在 `RecordingService` 里写 `if (keyword)…` 分支（违背「预留口」要求，且耦合）；用事件总线（过度设计）。

### D9：设置界面

在设置页新增「日程」分组：来源列表（导入文件按钮、添加订阅链接输入、各来源的最近成功时间/失败原因、刷新与删除）；`RangeSlider`（Material3，`valueRange = -10f..5f`，`steps = 14`）显示「开始前/后 X 分钟」文案，按值反映：拉杆值 `>0` 显示「开始前 N 分」，`<0` 显示「开始后 N 分」，`0` 显示「开始时」；近期（未来 24 小时）日程预览以便用户验证 ICS 是否解析正确。

## Risks / Trade-offs

- [Windows/自定义 `TZID` 无法映射] → 回落手机时区并在预览中如实展示出现项时刻，用户可据此发现偏差；常见 Windows 名内置映射。
- [不支持的 RRULE 导致漏掉后续课] → 日志告警，预览可见；后续按真实课表样本扩充支持集。
- [WorkManager 在厂商省电策略下不准时] → 启动时机会性刷新兜底；自动开录只依赖已缓存数据。
- [自动开录的误触发/不想录]：用户在窗口内进入应用即开录（可能是随手打开）→ 开录有明确提示，且通知栏与页面可随时停止；阈值可调。此行为由 spec 明确，属有意设计。
- [同一日程第一次录音被删后，该出现项视为「无关联课堂」，再次进入应用会再次自动开录] → 符合「无关联课堂才自动开录」的语义，视为可接受。
- [后台冷启动被系统杀死后用户从桌面再进] → 冷启动恢复会把原录音标为 `interrupted` 并关联同一 `scheduleKey`，因此不会自动再开录；用户可手动继续或新开，新开按 `-x` 命名。
- [`-x` 编号对用户改名的课堂的容错] → 以现有标题集合避让，宁可跳号也不重名。
- [数据库迁移失败风险] → 仅 `ADD COLUMN` 与新表，沿用 v1→v2 的非破坏性做法，`DatabaseMigrationTest` 覆盖 2→3。
- [应用已在后台时点桌面图标，系统可能只把任务调到前台而不送达新 Intent，导致 `onNewIntent` 不触发] → 将 `MainActivity` 设为 `singleTop` 以便送达时处理；是否送达只能真机确认（任务 7.4），若不送达需另行设计进入检测（如前台回到应用的生命周期事件）并回改本设计与 spec。

## Migration Plan

1. 先交付 `core` 纯逻辑与单元测试（解析、展开、判定、命名、停止触发器注册表），CI `:core:test` 通过。
2. 再交付数据库 3、日程仓库与订阅刷新、设置页日程分组。
3. 最后接入 `MainActivity` 启动判定、手动开始的标题跳过与 `RecordingService` 停止入口收口。
4. 回滚：功能均为叠加；若需回退，关闭启动判定入口即可恢复 v1 手动流程，数据库新增列与表保留不影响旧逻辑。

## Open Questions

- 默认订阅刷新间隔（当前取 12 小时周期、启动时 6 小时过期）与响应体上限（5 MB）可在真机体验后调整，不影响 spec。
