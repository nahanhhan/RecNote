# Tasks

## 1. 图标资源落盘

- [ ] 1.1 将用户提供的三个 24dp 矢量图标复制到 [`drawable`](../../app/src/main/res/drawable/)，命名为 `ic_add_24px.xml`、`ic_edit_24px.xml`、`ic_delete_24px.xml`，内容与 `C:\Users\Tony\Downloads` 下同名文件保持一致。验证：三个文件存在且与源文件逐字节一致（`fc /b` 比较通过）

## 2. 预设管理入口图标化

- [ ] 2.1 在 [`CloudSettingsPanel`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CloudSettingsPanel.kt) 将预设选择器 `Box` 与三个文字按钮 `Row` 合并为一个 `Row`（`Arrangement.spacedBy(4.dp)`）：选择器 `OutlinedButton` 以 `Modifier.weight(1f)` 占据行内剩余宽度（保留 `testTag("cloud-preset")` 与下拉菜单逻辑）；三个入口替换为 `IconButton` + `Icon(painterResource(...))`，`contentDescription` 分别为「新增预设」「重命名预设」「删除预设」；`enabled` 语义照搬（新增/重命名 `!busy`，删除 `!busy && presets.size > 1`）；`testTag` 照搬（`rename-preset`、`delete-preset`）并为新增入口补 `testTag("add-preset")`；点击回调、重命名/删除确认对话框、`message` 提示与 `presets`/`draft`/`presetDrafts`/`checks` 刷新序列保持不变。验证：CI 工程验证（编译、测试、lint）通过
- [ ] 2.2 核对 [`ImportAndPresetIntegrationTest`](../../app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt) 在本变更下无需改动（入口锚定用的 `testTag` 与对话框文字均未变）；若编译期发现问题则做最小修复。验证：CI 工程验证（编译、测试、lint）通过，测试文件除必要修复外无改动

## 3. 集成核对与真机验收

- [ ] 3.1 端侧自检：在 `scripts/tests` 下执行 `uv run python -m checks` 全部通过，并执行 `openspec validate cloud-preset-icon-buttons` 通过。验证：两条命令零退出码（CI 通过仅为工程验证，不代表真机验收）
- [ ] 3.2 真机验收（人工勾选）：新增、重命名、删除三个图标按钮与「配置预设」选择器位于同一行，选择器占据剩余宽度，界面不再有单独占行的三个文字按钮
- [ ] 3.3 真机验收（人工勾选）：读屏聚焦三个图标按钮，分别播报「新增预设」「重命名预设」「删除预设」
- [ ] 3.4 真机验收（人工勾选）：点击三个图标按钮分别触发原有行为（新增并切换预设、打开重命名对话框、打开删除确认对话框）；存在进行中的云端操作时三个按钮均禁用；仅剩一个预设时删除按钮禁用
- [ ] 3.5 真机验收（人工勾选）：亮色与暗色主题下图标均清晰可见；预设名较长时选择器文字截断、三个图标按钮不被挤压
