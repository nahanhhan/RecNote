# Spec Delta

## Purpose

定义 RecNote 如何接入并理解用户的 ICS 日程：支持本地导入与网络订阅，把日程中的单次与重复事件解析为带明确起止时刻的出现项，并在离线或刷新失败时保持可用，为日程驱动的自动录音提供可信的时间依据。

## ADDED Requirements

### Requirement: 导入本地 ICS 文件

系统 SHALL 允许用户通过系统文件选择器导入 `.ics` 文件作为日程来源。导入成功后该来源 MUST 持久保存在本机，不依赖原文件或选择器授权继续存在。解析失败或文件不含任何可识别事件时 MUST 拒绝导入并给出原因，且不改变已有来源。

#### Scenario: 导入有效课表文件

- **WHEN** 用户选择一个含有若干 `VEVENT` 的 `.ics` 文件
- **THEN** 来源出现在日程来源列表中，原文件被删除后其日程仍可用

#### Scenario: 导入无效文件

- **WHEN** 用户选择一个不是 ICS 格式或不含任何事件的文件
- **THEN** 系统提示导入失败原因，来源列表保持不变

### Requirement: 订阅 ICS 网络链接并定时刷新

系统 SHALL 允许用户添加 ICS 订阅链接。`webcal://` 链接 MUST 按 `https://` 处理；明文 `http://` 链接 MUST 被拒绝。订阅 SHALL 在添加时立即拉取一次，之后在应用进入时（距上次成功拉取超过刷新间隔）和系统定时任务中后台刷新，并允许用户手动立即刷新。刷新 MUST NOT 阻塞或延迟应用进入时的自动开录判定。

#### Scenario: 添加 webcal 订阅

- **WHEN** 用户输入 `webcal://example.edu/class.ics`
- **THEN** 系统以 `https://example.edu/class.ics` 拉取并解析，成功后保存来源与拉取时间

#### Scenario: 拒绝明文链接

- **WHEN** 用户输入 `http://example.edu/class.ics`
- **THEN** 系统拒绝添加并提示仅支持 HTTPS

#### Scenario: 定时刷新不阻塞进入

- **WHEN** 用户进入应用且某订阅已超过刷新间隔
- **THEN** 系统使用本机已缓存的日程立即完成自动开录判定，刷新在后台进行

### Requirement: 刷新失败时保留最近一次成功的日程

订阅刷新遇到网络错误、HTTP 错误或解析失败时，系统 MUST 保留该来源最近一次成功解析的日程继续使用，并在来源列表中记录并显示最近一次失败原因与最近一次成功时间。成功的刷新 MUST 整体替换该来源的日程，使上游删除的事件不再出现。

#### Scenario: 离线时仍可使用旧日程

- **WHEN** 订阅刷新因网络不可用失败
- **THEN** 该来源仍按上次成功拉取的内容提供日程，列表显示失败原因

#### Scenario: 上游删除事件后刷新

- **WHEN** 上游 ICS 删除了一节课后刷新成功
- **THEN** 该课不再作为日程出现项参与判定

### Requirement: 解析常见 ICS 事件与时间写法

系统 SHALL 解析 `VEVENT` 的标题（`SUMMARY`）、开始（`DTSTART`）及结束（`DTEND` 或 `DURATION`）时刻，支持折行续写、转义字符、带 `TZID` 的本地时间、UTC（`Z`）时间与浮动时间（按手机当前时区解释）。无法识别的 `TZID` MUST 回落为手机当前时区。仅有日期的全天事件 MUST NOT 参与自动开录判定。缺少 `SUMMARY` 的事件 MUST 使用非空的默认名称。

#### Scenario: 带时区的本地时间

- **WHEN** 事件为 `DTSTART;TZID=Asia/Shanghai:20260302T080000`
- **THEN** 出现项开始时刻为该时区 2026-03-02 08:00 对应的绝对时刻，与手机当前时区无关

#### Scenario: 全天事件

- **WHEN** 事件为 `DTSTART;VALUE=DATE:20260302`
- **THEN** 该事件不会触发自动开录

#### Scenario: 只有时长

- **WHEN** 事件无 `DTEND` 但有 `DURATION:PT1H40M`
- **THEN** 出现项结束时刻为开始时刻后 1 小时 40 分

### Requirement: 展开重复规则并处理例外

系统 SHALL 展开 `RRULE`（至少支持 `DAILY`、`WEEKLY`、`MONTHLY`、`YEARLY` 频率，及 `INTERVAL`、`COUNT`、`UNTIL`、`BYDAY`），并应用 `EXDATE` 排除项与 `RECURRENCE-ID` 单次覆盖（覆盖可改变该次的标题与起止时刻）。展开 MUST 只针对被查询的时间区间进行，且对不受支持的规则 MUST 忽略该规则的重复部分而保留首次出现，不得使整个来源解析失败。

#### Scenario: 每周重复且排除一次

- **WHEN** 事件每周一重复，`EXDATE` 排除了某个周一
- **THEN** 该周一不产生出现项，其余周一均产生

#### Scenario: 单次调课

- **WHEN** 某次出现带有 `RECURRENCE-ID` 的覆盖，改到了其他时刻
- **THEN** 原时刻不再有出现项，覆盖后的时刻有出现项

#### Scenario: 不支持的规则

- **WHEN** 事件使用了解析器不支持的 `RRULE` 组合
- **THEN** 来源仍解析成功，该事件只保留首次出现，并在日志中记录告警

### Requirement: 多来源合并

系统 SHALL 允许同时存在多个日程来源，并在查询时合并所有来源的出现项。删除某个来源 MUST 只移除该来源的日程，不影响已有课堂记录。

#### Scenario: 两个来源同一时刻都有日程

- **WHEN** 两个来源在同一时刻各有一个出现项
- **THEN** 两者都参与判定，由自动开录规则选出其中一个
