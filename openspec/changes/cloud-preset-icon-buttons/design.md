# Design

## Context

[`CloudSettingsPanel`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/ui/CloudSettingsPanel.kt) 当前把预设管理拆成两个相邻块：一个 `Box` 内的 `OutlinedButton`（`testTag("cloud-preset")`，`fillMaxWidth`，点击展开预设下拉菜单），紧接着一个 `Row` 并列三个 `TextButton`（新增预设无标签、`testTag("rename-preset")`、`testTag("delete-preset")`，删除按钮在 `presets.size > 1` 时可点）。三个文字按钮单独占一行，是屏幕上最散的一块。动机见 proposal.md - Why。

用户已提供三个 24dp 矢量图标素材（`C:\Users\Tony\Downloads` 下的 `add_24px.xml`、`edit_24px.xml`、`delete_24px.xml`，960×960 viewport 的 Material 风格路径，自带 `android:tint="?attr/colorControlNormal"`）。[`ImportAndPresetIntegrationTest`](../../app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt) 通过 `testTag` 与对话框文字锚定这三个入口，[`SettingsStore`](../../app/src/main/java/io/github/nahanhhan/lecturerecording/data/SettingsStore.kt) 的预设存储接口不在本变更范围内。

## Goals / Non-Goals

**Goals:**

- 三个预设管理入口图标化，并与「配置预设」选择器合并为同一行，收拢布局、缩减屏幕占用。
- 交互契约零变化：点击行为、禁用语义（`busy`、`presets.size > 1`）、对话框流程、`testTag`、提示文案全部保持，既有集成测试无需修改。
- 图标按钮具备中文无障碍内容描述。

**Non-Goals:**

- 不改变预设的存储结构、增删改逻辑与命名规则。
- 不引入溢出菜单（overflow）、长按 tooltip、图标+文字混排等新交互形态。
- 不新增图标依赖库，不重绘或修改用户提供的图标路径数据。

## Decisions

1. **布局合并为一个 `Row`**：原预设选择器 `Box` 与三个按钮的 `Row` 合并——选择器 `OutlinedButton` 加 `Modifier.weight(1f)` 占据行内剩余宽度，三个 `IconButton` 以默认 48dp 固定尺寸紧随其右，行内 `Arrangement.spacedBy(4.dp)`。备选是把图标按钮绝对定位覆盖在选择器右侧：`Box` 内叠放会增加命中区域与读屏顺序的复杂度，且长预设名会与图标重叠，弃用。
2. **图标资源直接落盘为 drawable**：把用户提供的三个矢量文件复制到 `app/src/main/res/drawable/`，命名 `ic_add_24px.xml`、`ic_edit_24px.xml`、`ic_delete_24px.xml`（沿用用户文件名，符合仓库现有 `ic_notebook.xml` 的 `ic_` 前缀惯例）。Compose 侧用 `painterResource(R.drawable.ic_add_24px)` 等加载，包进 `Icon`。备选是引 Material Icons Extended 依赖用现成图标：会新增依赖、包体变大，且风格与用户指定素材不一致，弃用。
3. **着色交给 `Icon` 默认 tint**：`Icon` 默认以 `LocalContentColor` 着色（`IconButton` 内为 `onSurfaceVariant` 一类的局部色），会覆盖矢量自带的 `?attr/colorControlNormal`，无需在代码里额外指定颜色，视觉上与原有文字按钮的标签色一致。备选是显式传 `tint`：多一处魔法值，且暗色主题需分别处理，收益低，弃用。
4. **入口控件用 Material3 `IconButton`，契约原样迁移**：三个 `IconButton` 各自 `contentDescription` 为「新增预设」「重命名预设」「删除预设」；`enabled` 表达式逐一照搬（新增/重命名为 `!busy`，删除为 `!busy && presets.size > 1`）；`modifier.testTag(...)` 照搬（`rename-preset`、`delete-preset`），并为新增入口补 `testTag("add-preset")` 使三个入口标签一致（既有测试不用该标签，纯新增不破坏兼容）。点击回调、对话框（重命名输入框、删除确认框）、`message` 提示与 `presets`/`draft`/`presetDrafts`/`checks` 刷新序列一字不改。

## Risks / Trade-offs

- [图标无语义文字，用户学习成本与误触风险] → 保留 `contentDescription` 供读屏；删除仍走「显示预设名称的确认对话框」；禁用语义不变，破坏性操作的保护网不削弱。
- [矢量自带 tint 与 `Icon` tint 叠加产生异常色] → `Icon` 的 `ColorFilter.tint` 覆盖矢量 tint，最终色为 `LocalContentColor`，与文字按钮标签色一致；真机验收时核对亮/暗主题下的图标可见性。
- [长预设名与三个 48dp 图标按钮争抢行宽] → 选择器 `weight(1f)` 可压缩，图标按钮固定尺寸不被挤压；预设名超长时选择器文字截断（`OutlinedButton` 默认省略行为）。真机验收核对长预设名场景。
- [androidTest 不在 CI 执行，UI 回归靠人工] → 本变更保持 `testTag` 与对话框文字不变，[`ImportAndPresetIntegrationTest`](../../app/src/androidTest/java/io/github/nahanhhan/lecturerecording/ImportAndPresetIntegrationTest.kt) 预期零改动仍可通过；CI 的 lint 覆盖 androidTest 源码编译；真机验收项列入 tasks.md 人工勾选清单。
