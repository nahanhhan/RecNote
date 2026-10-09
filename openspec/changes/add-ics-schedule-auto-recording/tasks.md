# Tasks

> 验收口径：每批以对应 CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过为工程验证；真机行为见第 7 组，由人工勾选。端侧提交前执行 `scripts/tests` 下 `uv run python -m checks`。

## 1. core：ICS 解析与出现项展开

- [x] 1.1 在 `core` 新增 ICS 行展开与属性解析（折行、转义、参数），并新增 `IcsParserTest` 覆盖折行、`\,` `\;` `\n` 转义；验证 `:core:test` 通过
- [x] 1.2 实现 `VEVENT` 抽取与时间解析（`TZID`/UTC/浮动/`VALUE=DATE`/`DURATION`、未知 `TZID` 回落、缺 `SUMMARY` 默认名），在测试中用上海时区、UTC、全天、只有时长四类夹具验证；`:core:test` 通过
- [x] 1.3 实现 `RRULE`（`DAILY/WEEKLY/MONTHLY/YEARLY`、`INTERVAL/COUNT/UNTIL/BYDAY`）、`EXDATE`、`RECURRENCE-ID` 覆盖的区间展开，含展开条数上限与不支持规则降级为首次出现；测试覆盖每周课表、排除一次、调课、`UNTIL` 边界、夏令时跨越与不支持规则告警；`:core:test` 通过
- [x] 1.4 定义 `Occurrence` 与稳定 `key`（来源 id + `UID` + 原始开始时刻），测试验证未变化事件刷新前后 key 不变、调课覆盖仍使用原始时刻；`:core:test` 通过

## 2. core：判定、命名与停止触发器抽象

- [x] 2.1 实现 `AutoStartWindow`（范围 -10..+5、默认 -3..+3、`lower ≤ upper` 归一化）与 `ScheduleResolver.resolveAutoStart/resolveActive`，测试覆盖默认区间边界（闭区间）、放宽到 -10、区间外、已有关联课堂、全天事件过滤、相邻两节课取最近/相等取较早；`:core:test` 通过
- [x] 2.2 实现 `SessionNaming`（首次日程名、其后「最大后缀+1」、避让重名、忽略非 `-整数` 形态标题），测试覆盖「高等数学→-1→-2」「删除 -1 后不复用 -2」「用户改名」；`:core:test` 通过
- [x] 2.3 定义 `StopReason`、`StopTrigger`、`ArmingPolicy`、`SessionContext` 与 `StopTriggerRegistry`（`requestStop` 幂等、`tick(now)` 按「日程结束后挂载」启动、`switchToManual()`、会话结束复位），并实现 `ManualStopTrigger`；测试覆盖课中不启动、到结束时刻启动一次、改为手动后不再启动且已启动者被停止、下一次会话不受影响、重复停止幂等；`:core:test` 通过

## 3. app：数据层与设置

- [x] 3.1 `LessonEntity` 新增 `scheduleKey`、`scheduleTitle`，新增 `ScheduleSourceEntity` 与 DAO 查询（按 `scheduleKey` 取课堂标题、来源增删改查），`LectureDatabase` 升到版本 3 并提供 `MIGRATION_2_3`（仅 `ADD COLUMN` 与新表）；`assembleDebug` 通过
- [x] 3.2 扩展 `DatabaseMigrationTest` 覆盖 1→3 与 2→3，验证旧课堂记录保留、新列默认空串、`schedule_sources` 表存在；`androidTest` 源码编译通过（`assembleDebug`/lint），真机/模拟器执行结果在第 7 组确认
- [x] 3.3 `SettingsStore` 新增自动开录区间（键 `auto_start_lower`/`auto_start_upper`，默认 -3/+3，越界值回落默认并保持 `lower ≤ upper`）；在 `core` 的 `AutoStartWindow` 单元测试里补充对存储值归一化的用例；`:core:test` 通过

## 4. app：日程来源仓库与订阅刷新

- [x] 4.1 新增日程仓库：本地导入（`OpenDocument` 选 `.ics`，复制到 `files/schedules/<id>.ics`，解析成功才提交，失败不改已有来源），`AppGraph` 持有；`:app:assembleDebug` 通过，并以 `ScheduleRepository` 可注入内存实现的方式把导入/替换/回滚逻辑放在可测处，补 `core` 或 `app` 单元测试验证「失败保留旧文件」；CI 通过
- [x] 4.2 实现订阅：`webcal→https`、拒绝 `http`、超时与体积上限（5 MB）、`ETag`/`If-None-Match`、成功后原子替换并记录 `lastSuccessAt`，失败记录 `lastError` 且保留旧日程；`:app:assembleDebug` 通过；对 URL 规范化与拒绝明文链接补单元测试，`:core:test` 或 `app` 单元测试通过
- [x] 4.3 在 `app/build.gradle.kts` 加 `androidx.work:work-runtime-ktx`，注册 12 小时周期、需网络的唯一刷新任务；应用进入时对超过 6 小时的订阅做后台机会性刷新，且判定路径不 await；`:app:assembleDebug` 与 `:app:lintDebug` 通过
- [x] 4.4 仓库提供「合并所有来源、展开 `[from,to]` 出现项」的只读查询（带按文件 mtime 失效的进程内缓存）；通过单元测试验证多来源合并、删除来源后不再出现；CI 通过

## 5. app：设置页「日程」分组

- [x] 5.1 新增日程设置面板：导入文件、添加订阅链接（含错误提示）、来源列表（最近成功时间/失败原因、手动刷新、删除，删除不影响已有课堂）；在 `SettingsScreen` 挂载；`:app:assembleDebug` 与 `:app:lintDebug` 通过
- [x] 5.2 面板加入 `RangeSlider`（`-10f..5f`，14 steps，拉杆不交叉），按正负值显示「开始前 N 分钟」「开始后 N 分钟」「开始时」，修改立即写入 `SettingsStore`；`:app:assembleDebug` 通过，真机验证见 7.2
- [x] 5.3 面板加入「未来 24 小时日程预览」，展示标题和开始/结束时刻，便于核对时区与重复规则；`:app:assembleDebug` 通过，真机验证见 7.1

## 6. app：自动开录、命名与停止入口收口

- [x] 6.1 `MainActivity` 增加启动判定：`onCreate`（`savedInstanceState == null` 且 `MAIN/LAUNCHER`）与 `onNewIntent` 同条件，等待 `graph.initialized` 后在 `lessonOperations` 内检查无录音/导入，调用 `resolveAutoStart`，命中则经 `beginRecording` 发送带 `title/course/schedule_key/schedule_title` 的 `START`；旋转重建不触发；`:app:assembleDebug` 通过
- [x] 6.2 把首页「开始课堂录音」改为先调用 `resolveActive`：命中则跳过对话框，首次用日程名，已有关联课堂则用 `SessionNaming` 生成 `-x`；未命中保留 v1 对话框；`:app:assembleDebug` 通过
- [x] 6.3 `RecordingService.runRecording` 接收并写入 `scheduleKey/scheduleTitle`；服务内增加 `StopTriggerRegistry`，把 `STOP` action 与页面停止都改经 `requestStop(StopReason.MANUAL)`，仅注册 `ManualStopTrigger`，未注册自动触发器时不调度 tick；确认停止后的收尾状态与 v1 一致；`:app:assembleDebug` 与 `:app:lintDebug` 通过
- [x] 6.4 确认日志中不记录订阅链接中的凭据片段（查询串脱敏）；运行 `uv run python -m checks`（含 `log_redaction`）通过
- [x] 6.5 统一「开始录音后进入录音详情」：自动开录、日程内手动开始与对话框手动新建三条路径在录音开始后均直接导航到该课堂录音详情页，不停留在首页；删除自动开录 Toast 提示及其字符串；`:app:assembleDebug` 与 `:app:lintDebug` 通过，`uv run python -m checks` 通过

## 7. 文档与真机验收

- [x] 7.1 更新 `docs/ARCHITECTURE.md`：数据库版本 3、日程来源与出现项、自动开录判定、停止触发器预留口（含「运动传感器/关键词须在日程结束后挂载」「本次改为手动」约定）；对照文档核对与实现一致
- [x] 7.2 更新 `README.md` 说明自动开录行为、阈值滑块、日程导入与订阅的用法；`uv run python -m checks` 通过
- [x] 7.3 运行 `openspec validate add-ics-schedule-auto-recording --strict` 通过
- [x] 7.4 真机验收（人工勾选）：导入真实教务系统 `.ics` 并核对预览时刻；订阅 HTTPS 链接并离线进入仍可判定；课前 2 分钟、迟到 5 分钟（放宽到 -10）、区间外三种进入场景；应用在后台时点桌面图标再次进入也会判定（依赖 `singleTop` 的 `onNewIntent` 在真机上实际送达，若不送达需改用其他进入检测并更新设计）；中断后再录得到「课名-1」「课名-2」且无标题对话框；旋转不重复触发；通知栏停止与页面停止收尾正常
- [ ] 7.5 真机验收（人工勾选）：自动开录与手动开始（含日程命名与对话框流程）后均直接进入录音详情页，且不再出现自动开录 Toast
