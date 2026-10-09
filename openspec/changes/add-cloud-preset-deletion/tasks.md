# Tasks

## 1. 存储层删除接口

- [x] 1.1 在 [`SettingsStore`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 新增 [`deletePreset(id)`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt:88)：校验预设存在（复用「预设不存在」语义）；仅剩一个预设时以「至少保留一个预设」拒绝且不产生改动；从 `cloud_presets` 移除并按 `preset_<id>_` 前缀清除其全部键（含 `_tested`），不动 `cloud_<provider>_` 旧版共享键；删除激活预设时把 `cloud_active_preset` 落到剩余列表首个，删除非激活预设不动激活键。验证：CI 工程验证（编译、测试、lint）通过
- [ ] 1.2 在 [`ImportAndPresetIntegrationTest`](../../app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt) 增加存储层断言：删除激活预设后激活回落到剩余首个；被删预设前缀键清空而其余预设键与 `_tested` 保留；最后一个预设删除被拒绝且列表不变；删除后新增预设为默认空白配置、不继承旧密钥与模型。验证：断言覆盖 [`cloud-presets` spec](specs/cloud-presets/spec.md) 四条 Requirement 的存储层场景，CI 工程验证通过

## 2. 设置界面删除入口

- [ ] 2.1 在 [`CloudSettingsPanel`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CloudSettingsPanel.kt) 的「新增预设/重命名预设」按钮行追加「删除预设」按钮（`testTag("delete-preset")`），点击弹确认对话框（标题「删除预设」、正文含预设名称、确认「删除」、取消「取消」）；仅剩一个预设或 `busy` 时禁用；确认后执行删除并刷新预设列表、把 `draft` 重取为新激活预设配置、移除被删预设草稿、清空 `checks`、提示「已删除预设」；取消不产生任何改动。验证：CI 工程验证（编译、测试、lint）通过
- [ ] 2.2 在 [`ImportAndPresetIntegrationTest`](../../app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt) 增加 UI 流程测试：确认删除后列表与激活预设更新、取消路径无改动、仅剩一个预设时删除入口禁用。验证：UI 测试覆盖 [`cloud-presets` spec](specs/cloud-presets/spec.md) 入口确认与拒绝场景，CI 工程验证通过
- [ ] 2.3 同步 [`README.md`](../../README.md) 预设功能说明为「支持新增、重命名和删除」。验证：README 行为描述与实现一致，CI 工程验证通过

## 3. 集成核对与真机验收

- [ ] 3.1 端侧自检：在 `scripts/tests` 执行 `uv run python -m checks` 全部通过，并执行 `openspec validate add-cloud-preset-deletion` 通过。验证：两条命令零退出码（CI 通过仅为工程验证，不代表真机验收）
- [ ] 3.2 真机验收（人工勾选）：删除非激活预设后，当前激活预设及其配置、测试状态保持不变
- [ ] 3.3 真机验收（人工勾选）：删除当前激活预设后自动激活剩余第一个预设，界面显示其保存的配置，无被删预设草稿残留
- [ ] 3.4 真机验收（人工勾选）：删除确认对话框选「取消」无任何改动；仅剩一个预设时「删除预设」入口禁用
- [ ] 3.5 真机验收（人工勾选）：删除已保存密钥且测过的预设后重启应用，其密钥与配置无残留；随后新增预设为空白默认配置
