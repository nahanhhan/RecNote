# Proposal

## Why

设置-云端笔记整理中「新增预设」「重命名预设」「删除预设」三个文字按钮独占一行，在屏幕上占比大、排列分散，预设管理操作与「配置预设」选择器割裂。将三个入口改为图标按钮并与「配置预设」选择器同行呈现，可以收拢预设管理区域、减少视觉干扰，让云端配置表单的主体（供应商、地址、模型、密钥）更靠前。

## What Changes

- 「新增预设」「重命名预设」「删除预设」三个入口从文字按钮（TextButton）改为图标按钮（IconButton），使用用户提供的 24dp 矢量图标（add / edit / delete）。
- 三个图标按钮与「配置预设」选择器放在同一行：选择器占据行内剩余宽度，三个图标按钮以固定尺寸紧随其右。
- 保留既有交互契约：三个入口的点击行为（新增并切换、打开重命名对话框、打开删除确认对话框）、禁用语义（进行中的云端操作时禁用；仅剩一个预设时删除禁用）、以及现有测试标签（`rename-preset`、`delete-preset`）均不变。
- 每个图标按钮 MUST 提供中文无障碍内容描述（「新增预设」「重命名预设」「删除预设」），保证读屏可用。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `cloud-presets`: 预设管理入口的呈现形式发生变化——「删除预设入口与确认」等要求中描述的「新增预设/重命名预设/删除预设」文字按钮并列，改为三个图标按钮与「配置预设」选择器同行；新增「预设管理图标入口」要求，约束图标化呈现、同行布局、无障碍描述与禁用语义。

## Impact

- [`CloudSettingsPanel.kt`](app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CloudSettingsPanel.kt)：预设选择器 `Box` 与三个操作按钮 `Row` 合并为一个 `Row`（选择器 `weight(1f)`，图标按钮固定尺寸），文字按钮替换为 `IconButton` + `Icon(painterResource(...))`。
- 新增 drawable 资源 `app/src/main/res/drawable/ic_add_24px.xml`、`ic_edit_24px.xml`、`ic_delete_24px.xml`（素材来自用户提供的 `C:\Users\Tony\Downloads` 下同名文件）。
- [`ImportAndPresetIntegrationTest.kt`](app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt)：既有流程依赖 `testTag` 与对话框文字，预期无需改动；如为「新增预设」补 `testTag` 则同批同步。
- 无存储层、数据模型、网络层变更；不改变预设的增删改行为本身。
- 归档顺序约束：`cloud-presets` 能力的主 spec 尚未建立（由 in-progress 的 `add-cloud-preset-deletion` 首次引入），本 change 的 MODIFIED delta MUST 在该 change 归档之后归档，否则主 spec 不存在时 MODIFIED 操作无法应用。
