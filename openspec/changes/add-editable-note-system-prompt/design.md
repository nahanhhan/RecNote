# Design

## Context

`ChatProtocol.instruction` 固定为 system 消息；工具 description 也重复了「不添加知识/解释/例子」等写作限制。`NotesService` 分批构造请求，`JobEntity` 已保存来源快照但没有保存提示词。设置使用 `SettingsStore`，应用已支持设置二级页面导航。

## Goals / Non-Goals

目标是让用户实际控制发给模型的系统提示词，提供日期与标题变量，并保证同一任务续跑的一致性。保留应用的结构化保存/来源校验，不增加任意代码执行或多模板管理。

## Decisions

1. `NotePrompt` 集中提供默认模板、原版 legacy 提示词和纯字符串渲染函数。仅精确替换 `{{datetime}}` / `{{title}}`，使用单次正则回调；变量值中的大括号、美元符号和反斜杠保持字面量，不递归展开。未知变量保持原文。模板要求非空、最多 20,000 个字符，保存时保留空白和换行。
2. `SystemPromptTime` 使用 Android `DateFormat.getDateFormat(context)` / `getTimeFormat(context)` 加系统当前时区，遵循区域格式和用户 12/24 小时偏好；不自行写死日期格式。标题来自录音的 `scheduleTitle`，无绑定则用 `title`，不使用整理时恰好正在进行的另一日程，也不使用 course 字段。
3. 应用级 `note_system_prompt` 存于本机设置，缺失时显示默认模板。编辑草稿旋转后保留，只有「保存」才生效，「恢复默认」修改草稿后由用户保存；系统/顶栏返回均回设置。插入变量使用当前光标或选区，预览明确使用示例标题。
4. 创建 Job 时读取模板，替换变量并保存为 `jobs.systemPrompt`，所有批次和保存确认复用该值。Room schema 4 用 `ALTER TABLE jobs ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT ''` 迁移，1→4、2→4 与 3→4 均保留数据。空串只代表旧任务，继续使用原始 legacy 提示词，不能因设置变化混入新风格；新任务展开为空时拒绝创建并给出提示。
5. `ChatProtocol.initial` 接收明确的系统提示词参数，生产整理入口传任务快照，不追加隐藏的固定写作指令。工具说明只描述保存格式和来源约束，移除重复风格要求。连接测试仍用 legacy 合成测试提示词，保持接口能力判定独立于个性化写作。
6. `CloudRequests.loggable` 把 system/developer 消息正文替换为省略标记，Debug 不记录新模板或展开文本；不改其他日志级别的现有规则。不得在测试中使用真实资料或密钥。

## Risks / Trade-offs

- 自定义提示词可能要求模型返回应用不支持的格式：编辑页说明输出仍按笔记格式保存，结构校验错误保留当前恢复方式。
- 全局编辑会影响不同供应商的新任务：界面说明适用于所有预设，旧任务不变；本轮不做逐预设配置。
- 未支持变量按原文保留：变量帮助只列两个已支持变量，预览显示实际展开结果。

## Migration Plan

先提交模板/协议逻辑与测试，通过 CI 后接入设置、任务快照与非破坏性迁移。旧任务的空快照沿用原始提示词，旧笔记不重新整理。完成后正常推送、发布 `v0.1.8-alpha` APK，保留固定签名和历史资源。

## Verification

core 单测覆盖变量替换、标题选择、特殊字符/非递归、未知变量、模板保存校验、自定义 system 消息和日志省略。Android 仪器测试覆盖设置重读、3→4 迁移、草稿/保存/默认恢复及系统时间格式；真机结果按 tasks.md 人工确认。端侧新增只读检查同批注册默认入口，OpenSpec 用官方 CLI 校验，编译、单测、lint 与 APK 由 GitHub CI 完成。

日期格式依据：[Android DateFormat 官方说明](https://developer.android.com/reference/android/text/format/DateFormat)。

同批其他调整：CI 增加 `:app:compileDebugAndroidTestKotlin`，确保新 UI/时间/迁移仪器测试源码可编译，不宣称已在设备执行；对应规则同步 `config.yaml`。新增 `prompt_contracts` 只读检查并注册默认入口，约束工具说明和请求日志边界。系统时间使用系统区域的独立 configuration context，避免应用单独语言设置影响系统格式。更新工具协议文档及两个离线请求示例中的工具说明。
