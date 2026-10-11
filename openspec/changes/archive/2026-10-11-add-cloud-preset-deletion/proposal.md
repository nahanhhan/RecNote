# Proposal

## Why

设置-云端笔记整理的配置预设目前只有「新增预设」和「重命名预设」，建错或不再需要的预设无法移除：列表越积越长，且预设中保存的 API 密钥等配置无法清理，存在残留风险。

## What Changes

- 云端笔记整理设置新增「删除预设」入口，作用于当前选中的预设，执行前弹出确认对话框。
- 删除预设时连带清除该预设保存的云端配置与连接测试状态；其他预设不受影响。
- 保证始终至少保留一个预设：仅剩一个预设时删除入口禁用，删除接口对最后一个预设拒绝执行。
- 删除当前激活的预设后自动切换到剩余列表中的第一个预设，界面显示其保存的配置。
- README 的预设功能说明同步补充删除能力。

## Capabilities

### New Capabilities

- `cloud-presets`: 云端笔记整理配置预设的管理边界：至少保留一个预设的不变量、删除确认、删除后的激活回落与预设数据清理。

### Modified Capabilities

（无。现有规格仅 `model-download`，不涉及预设行为。）

## Impact

- [`SettingsStore.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt)：新增删除预设的存储接口（列表维护、`preset_<id>_` 前缀数据清除、激活预设回落）。
- [`CloudSettingsPanel.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CloudSettingsPanel.kt)：新增「删除预设」按钮与确认对话框，单预设或忙碌时禁用，删除后刷新草稿与状态。
- [`ImportAndPresetIntegrationTest.kt`](app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt)：补充删除行为的存储层断言与 UI 流程测试（androidTest，真机执行）。
- [`README.md`](README.md)：预设功能说明由「支持新增和重命名」改为「支持新增、重命名和删除」。
- 无 core 模块、协议或存储结构变更；预设数据仍存于 `settings` SharedPreferences。
