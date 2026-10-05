# Design

## Context

见 [proposal.md](proposal.md) 的 Why。当前图标资产的构成决定了改法：

- [`artwork/app-icon.svg`](../../../../artwork/app-icon.svg) 为源图稿：1280×1280 画布，背景是内嵌白色 PNG，其上是七条矢量路径（三个圆、两个小圆、一个红圆、两个三角形，仅由圆与三角形构成）。路径外框为 x∈[255, 1026]、y∈[209.88, 1071.76]（771×861.88），但右侧白色三角形楔形会遮挡深色环形右半（成品是 C 形环 + 中心红点 + 两端圆头），**可见墨迹外框**实际为 x∈[255, 909]、y∈[255, 1025]（654×770，中心 (582, 640)），几乎占满画布。
- 三份 Android 矢量图复制了同一组 pathData：自适应前景 [`ic_launcher_foreground.xml`](../../../../app/src/main/res/drawable/ic_launcher_foreground.xml)（108dp/1280 视口）、旧式 [`mipmap-anydpi/ic_launcher.xml`](../../../../app/src/main/res/mipmap-anydpi/ic_launcher.xml) 与 [`ic_launcher_round.xml`](../../../../app/src/main/res/mipmap-anydpi/ic_launcher_round.xml)（48dp，前置全幅白色背景路径）。[`mipmap-anydpi-v26`](../../../../app/src/main/res/mipmap-anydpi-v26) 的 `adaptive-icon` 仅引用 `@drawable/ic_launcher_foreground` 与 `@color/ic_launcher_background`（`#FFFFFF`）。
- 自适应图标 108dp 画布只有中心 72dp（安全区约 66dp）不被遮罩裁切；图形长边当前占画布 67.3%，必然触边被裁。
- 「课堂录音笔」出现于 [`AndroidManifest.xml`](../../../../app/src/main/AndroidManifest.xml)（`android:label`）、[`MainActivity.kt`](../../../../app/src/main/java/io/github/nahanhhan/lecturerecording/MainActivity.kt)（首页顶栏）、[`RecordingService.kt`](../../../../app/src/main/java/io/github/nahanhhan/lecturerecording/recording/RecordingService.kt)（通知标题）三处硬编码文案；[`README.md`](../../../../README.md) 版本行受 [`version_consistency.py`](../../../../verification/checks/checks/version_consistency.py) 检查约束。

## Goals / Non-Goals

**Goals:**

- 一条几何规则覆盖全部图标资产与预览图：图形等比缩放、居中、长边占画布 50%。
- 缩放以变换实现，七条 pathData 原文不动，消除多文件手改路径的漂移面。
- 三处用户可见文案一次性统一为「RecNote」，并保持版本号与 README 一致。

**Non-Goals:**

- 不改图形造型、配色、背景（仍为纯白）；不重绘、不简化路径。
- 不抽取 `strings.xml`、不重构文案基础设施（沿用现有硬编码风格）。
- 不改 [`mipmap-anydpi-v26`](../../../../app/src/main/res/mipmap-anydpi-v26) 的 `adaptive-icon` 引用结构，不引入 PNG 各密度 mipmap。
- 不回改 [`IMPLEMENTATION_PLAN.md`](../../../../IMPLEMENTATION_PLAN.md)、[`verification/ICON_UPDATE.md`](../../../../verification/ICON_UPDATE.md) 等历史记录文档。
- 不新增静态检查项（图标几何属视觉口径，不在 `verification/checks` 的检查面内）。

## Decisions

1. **留白口径取「图形长边 = 画布边长 50%」**。用户表述「四周留白 50%」按"每边留白约为图形的 50%"解释，与"四边留白合计占画布 50%"在数值上收敛为同一结果：图形外框长边 640/1280，每边留白 ≥25% 画布（即 ≥ 图形的 50%）。替代口径「缩到当前尺寸的 50%」会使图形仅占画布约 33%、留白约为图形的 116%，与「留白 50%」不符，弃用。数值验收口径已写入 [specs/app-branding/spec.md](specs/app-branding/spec.md)。
2. **以变换缩放代替重算 pathData**。Android 矢量图用 `<group android:scaleX/scaleY/translateX/translateY>` 包裹七条路径（旧式图标的全幅白色背景路径留在组外）；SVG 用 `<g transform="translate(...) scale(...)">` 包裹同一组路径。变换 p' = s·p + t，**缩放锚点取中心红圆圆心 (640, 640)**——用户口径：红圆为几何中心，而非可见外框左右取中（C 形开口在右，按可见外框取中会整体偏左）；等比缩放使可见墨迹外框（654×770）长边恰为 640：s = 640/770 ≈ **0.83117**，t = 640(1−s) ≈ **(108.05, 108.05)**（精确到 ±1 视口单位即可）。长边基准取可见外框而非路径外框（861.88）——右侧楔形为白色会遮挡，按路径外框计算长边仅 44.7%。替代方案"逐条改写 pathData"易错且难以核对，弃用；"改 viewport"会连背景一起缩放，无法保持白色底满幅，弃用。
3. **预览图从同一几何参数重新生成**。[`artwork/app-icon-preview.png`](../../../../artwork/app-icon-preview.png) 优先用可直接渲染 [`app-icon.svg`](../../../../artwork/app-icon.svg) 的工具（resvg/Inkscape/浏览器导出等）重出；若端侧无渲染器，用一次性 Pillow 脚本按同一变换重绘（七形状均为圆与三角形，可解析绘制，4 倍超采样抗锯齿）。两条路径任选其一，验收以"预览图满足同一 50% 口径"为准，脚本无需入库。
4. **通知标题同步改为「RecNote」**。通知标题当前就是软件名，属"软件 title"的用户可见呈现；只改启动器与顶栏会造成同一软件两个名字，弃用。
5. **仅递增 `versionName` 至 `0.1.2-alpha`，`versionCode` 保持 2**。按用户确认的口径：兼容性升级不递增 versionCode（同签名产物覆盖安装）；README 版本行同批修改以通过 [`version_consistency.py`](../../../../verification/checks/checks/version_consistency.py)。
6. **外观验收走真机清单**。CI 只能做工程验证（编译/测试/lint），图标留白与名称显示属视觉验收，在 [`verification/DEVICE_CHECKLIST.md`](../../../../verification/DEVICE_CHECKLIST.md) 增补一条核对项，不伪造自动化外观断言。

## Risks / Trade-offs

- [留白口径与用户预期有偏差（如希望更小/更大）] → 缩放参数集中在单一变换（s、t 两个数），按评审意见改一处即可全量生效。
- [三份矢量图 + SVG 共四处需保持几何一致] → 四处用同一组变换参数，pathData 原文不改；提交前人工 diff 参数值一致性。
- [预览图与实际图标不一致] → 从同一 SVG/几何参数生成；真机清单核对安装包实际效果与 README 预览一致。
- [CI 无法验证外观] → CI 通过仅表述为工程验证；外观与名称显示以真机清单人工验收为准。
- [README 改动触碰"README 最小改动"规矩] → 仅改版本行（检查项强制）与图标 alt 文案（用户可见名称变化需要通知），属允许范围。

## Migration Plan

纯资源与文案变更，直接合入主干：新产物（`versionName` 0.1.2-alpha，`versionCode` 2）覆盖安装已装版本，用户数据不受影响。回滚策略为 revert 本次提交（无数据迁移、无协议变化）。
