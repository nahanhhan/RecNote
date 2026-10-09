# Design

## Context

预设持久化全部集中在 [`SettingsStore`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt)：`cloud_presets` 键存 `CloudPreset(id, name)` 列表，`cloud_active_preset` 存激活预设 id，每个预设的配置与测试状态存于 `preset_<id>_` 前缀的键（其中密钥经 AndroidKeyStore 加密）。现有 [`addPreset()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) / [`renamePreset()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 走「存储接口 + [`CloudSettingsPanel`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CloudSettingsPanel.kt) 按钮」的模式；[`presets()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 在列表为空时会重建「预设一/预设二」，[`activePreset()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 在激活 id 失效时回落到列表首个。行为契约见 [`cloud-presets` spec](specs/cloud-presets/spec.md)。

## Goals / Non-Goals

**Goals:**

- 删除预设的存储层与 UI 层闭环（确认、拒绝、回落、数据清理）。
- 删除路径与现有新增/重命名路径保持同一交互与错误提示模式。
- 删除行为可测试：存储层断言 + Compose UI 流程测试。

**Non-Goals:**

- 不改变预设的存储结构、加密方式或旧版 `cloud_<provider>_` 共享供应商配置的行为。
- 不调整新增预设的命名规则（删除后新增仍按 `预设 N` 命名，允许与历史重名）。
- 不引入预设导入/导出、排序、置顶等新能力。

## Decisions

1. **删除接口放在 [`SettingsStore`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt)**，与 [`addPreset()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) / [`renamePreset()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 并列：预设的读写、激活回落都在该类，UI 只做交互编排。备选是把删除逻辑下沉 core 纯函数，但预设持久化本就在 app 层，单独抽纯函数只为可测性，收益低且偏离现状。
2. **删除接口签名 `deletePreset(id: String)`**：先校验预设存在（复用现有「预设不存在」报错语义），再校验 `list.size > 1` 否则 `require` 失败提示「至少保留一个预设」（与现有 `require` 抛 `IllegalArgumentException` 由 UI 转 `message` 的模式一致）。删除非激活预设不动 `cloud_active_preset`；删除激活预设时把 `cloud_active_preset` 落到剩余列表首个，保证 [`cloud()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 立即有有效预设可用。
3. **数据清理按前缀清除 `preset_<id>_` 全部键**：遍历 `preferences.all.keys` 过滤前缀后统一 `remove`，连同 `preset_<id>_tested` 一并清除。备选是逐个枚举已知键名（base/model/key/strict/provider/photos/tested），但写入侧键集合可能随 [`write()`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 演进，按前缀清除更不易漏。`cloud_<provider>_` 旧版共享键不动（它们是历史兼容的供应商档案，不属于单个预设）。
4. **UI 在 [`CloudSettingsPanel`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CloudSettingsPanel.kt) 的「新增预设/重命名预设」按钮行追加「删除预设」**，加 `testTag("delete-preset")`；点击弹确认 `AlertDialog`（标题「删除预设」，正文含预设名称，确认按钮「删除」、取消按钮「取消」），确认后调用删除接口并刷新 `presets`/`draft`，清空该预设在 `presetDrafts` 的草稿与 `checks`，`message` 提示「已删除预设」。仅剩一个预设或 `busy` 时按钮禁用。备选是仿照重命名用输入对话框内嵌删除按钮，交互上删除属破坏性操作，独立确认更不易误触。
5. **测试放 [`ImportAndPresetIntegrationTest`](../../app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt)**（androidTest）：存储层断言（激活回落、前缀键清除、最后预设拒绝、其他预设不受影响）与 Compose 流程（点删除→确认→列表与激活变化；取消无变化；单预设禁用）。原因：[`SettingsStore`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 依赖 SharedPreferences 与 AndroidKeyStore，只有仪器环境可跑；与既有预设测试同文件同模式。CI（`:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`）承担编译、lint 与单测验证，行为验证按 tasks.md 真机人工勾选。

## Risks / Trade-offs

- [按前缀清除误删同前缀无关键] → 前缀 `preset_<id>_` 含完整 UUID/固定 id，与其他键命名空间不冲突；仅遍历删除匹配前缀的键，不动 `cloud_presets`/`cloud_active_preset` 本身。
- [删除激活预设瞬间 UI 草稿错位] → 删除成功后统一执行「移除草稿 → 刷新 `presets` → 重取 `cloud()` 作为 `draft` → 清空 `checks`」序列，不复用旧 `draft`。
- [`presets()` 空列表自愈重建与删除语义冲突] → 拒绝删除最后一个预设，列表永不会为空，重建分支不被触发。
- [androidTest 不在 CI 执行] → CI 仍验证编译与 lint（androidTest 源由 lint 覆盖），行为验收明确列入 tasks.md 真机清单，符合「验证结论以 CI 为准、真机验收人工勾选」的分工。
