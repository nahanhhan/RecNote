# Proposal

## Why

commit `cd44d5dc` 启用了新图标，但图形几乎顶满 1280×1280 画布（可见外框约 771×862），在启动器自适应图标的圆形/圆角遮罩下几乎无留白、边缘被裁切，视觉上"太大"；用户要求四周留白 50%。同时软件在启动器、主界面顶栏和录音通知中仍显示旧名"课堂录音笔"，需要统一改为"RecNote"。

## What Changes

- 图标图形等比缩小、以中心红圆为几何中心（圆心与画布中心重合）：图形可见外框长边缩至占图标画布边长的 50%（即落入画布中心 50%×50% 区域），四边留白各 ≥ 画布边长的 25%；同一几何规则同步应用于 [`artwork/app-icon.svg`](../../../artwork/app-icon.svg)、自适应图标前景 [`ic_launcher_foreground.xml`](../../../app/src/main/res/drawable/ic_launcher_foreground.xml)、旧式 [`mipmap-anydpi`](../../../app/src/main/res/mipmap-anydpi) 矢量图标（含 round）与 [`artwork/app-icon-preview.png`](../../../artwork/app-icon-preview.png) 预览图。
- 用户可见软件名称统一改为「RecNote」：启动器标签 [`AndroidManifest.xml`](../../../app/src/main/AndroidManifest.xml)、主界面顶栏 [`MainActivity.kt`](../../../app/src/main/java/io/github/nahanhhan/lecturerecording/MainActivity.kt)、录音服务通知标题 [`RecordingService.kt`](../../../app/src/main/java/io/github/nahanhhan/lecturerecording/recording/RecordingService.kt)；运行时用户可见文案 MUST NOT 再出现"课堂录音笔"。
- [`README.md`](../../../README.md) 同步：版本行递增与图标 alt 文案（"课堂录音笔图标"→"RecNote图标"）；`versionName` 提升至 `0.1.2-alpha`，`versionCode` 保持 2（用户口径：兼容性升级不递增 versionCode）。
- 图形七条路径、配色（`#222222`/`#ffffff`/`#d32f2f`）和纯白背景 MUST NOT 改变，仅改几何尺寸与文案。

## Capabilities

### New Capabilities

- `app-branding`: 应用品牌呈现的持久行为——启动器图标（自适应/圆形/旧式）中图形的留白与居中规则，以及用户可见软件名称的统一文案口径。

### Modified Capabilities

（无；`model-download` 等既有能力的需求不变。）

## Impact

- **资源**：[`ic_launcher_foreground.xml`](../../../app/src/main/res/drawable/ic_launcher_foreground.xml)、[`mipmap-anydpi/ic_launcher.xml`](../../../app/src/main/res/mipmap-anydpi/ic_launcher.xml)、[`mipmap-anydpi/ic_launcher_round.xml`](../../../app/src/main/res/mipmap-anydpi/ic_launcher_round.xml)（`mipmap-anydpi-v26` 仅引用上述资源、预期不需改动）、[`artwork/app-icon.svg`](../../../artwork/app-icon.svg)、[`artwork/app-icon-preview.png`](../../../artwork/app-icon-preview.png)
- **代码**：[`AndroidManifest.xml`](../../../app/src/main/AndroidManifest.xml)（`android:label`）、[`MainActivity.kt`](../../../app/src/main/java/io/github/nahanhhan/lecturerecording/MainActivity.kt)（顶栏标题）、[`RecordingService.kt`](../../../app/src/main/java/io/github/nahanhhan/lecturerecording/recording/RecordingService.kt)（通知标题）
- **版本/文档**：[`app/build.gradle.kts`](../../../app/build.gradle.kts)（`versionName`/`versionCode`）、[`README.md`](../../../README.md)（版本行受 [`version_consistency.py`](../../../verification/checks/checks/version_consistency.py) 检查约束，必须与 gradle 同步）
- **范围假设**：留白口径按"每边留白约为图形的 50%"解释，即图形可见外框长边占画布 50%，居中口径以中心红圆圆心与画布中心重合；`versionName` 递增至 `0.1.2-alpha` 而 `versionCode` 保持 2（兼容性升级不递增）；`IMPLEMENTATION_PLAN.md`、[`verification/ICON_UPDATE.md`](../../../verification/ICON_UPDATE.md) 等历史记录文档不回改。
- **兼容性**：纯外观与文案变化，无数据迁移、无协议/接口影响；录音、识别、下载等行为不变。
