# RecNote：从录音到笔记的一站式体验

<img src="artwork/app-icon-preview.png" alt="RecNote图标" width="96">

一款安卓课堂/会议录音工具：录音和拍照 → 手机本地转写 → 手动云端整理 → 图文笔记 → PDF / Markdown。

当前版本为 **0.1.5-alpha**。首版代码已经实现；长时间后台稳定性、识别效果和真实云端往返仍需在目标手机上验收，不能将编译通过视为发布验收通过。

## 已实现

- 课堂列表、麦克风录音、暂停/继续、持续通知控制、异常后已保存音频恢复。
- 16 kHz 单声道 WAV 按 30 秒分文件写入，每秒更新文件头并同步落盘。
- 独立 `:asr` 进程中的 sherpa-onnx 1.12.27，默认 FireRedASR2 AED INT8，备用 CTC；最多 15 秒短段识别，临时结果合并以控制积压。
- App 内模型下载、HTTP Range 续传、固定 SHA-256 校验、原生解压、校验/安装进度及文件校验清单；文件哈希随解压写入计算，减少重复读盘。
- 详情页删除录音；列表长按或点击「多选」后全选、批量删除。录音、照片、原稿、笔记及本地导出缓存一并清理，处理中记录暂不允许删除。
- 导入 MP3、AAC、WAV、M4A，转换为 16 kHz 单声道短段，在本机转写、回听与整理文字笔记；不要求麦克风权限，不追加录音或照片。导入/转写中断后可手动继续。
- 模型配置预设默认提供「预设一」「预设二」，支持新增和重命名；旧配置保留，各预设保存独立配置、加密密钥和测试状态。
- CameraX 拍照，按音频样本计时，保留原图、拍摄日期、课堂内序号和固定 `ast_` 文件名。
- 段落/照片回听、原稿编辑、照片选择、云端来源版本快照。
- HTTPS Chat Completions 工具调用、严格结构与来源校验、数据库事务、批次幂等保存、工具回执和手动恢复。
- DeepSeek、OpenRouter、OpenCode Zen、OpenCode Go、OpenAI 和自定义供应商入口，各自加密保存密钥及配置；可读取供应商模型列表或手动填写。
- 基础地址及完整 `/chat/completions` 地址自动规范化；连接、文字笔记保存、读图分步测试，失败时显示供应商原因和 HTTP 状态，不发送课堂材料。
- 可关闭「让模型读取照片内容」以只整理文字，照片仍按拍照时的录音时间插入对应笔记小节，不依赖读图模型。DeepSeek 自动使用兼容参数并关闭思考模式，应用仍严格校验结构和来源。
- 离线 Markdown、表格、代码和 KaTeX 公式阅读；Android 保存为 PDF；Markdown 与原图 ZIP 分享。
- 课程日程：设置页可导入本地 `.ics` 文件或订阅 `https://`/`webcal://` 日程链接（定时刷新，刷新失败时继续使用上次成功的日程）。从桌面进入应用时，若当前时间落在某节课开始时刻的「自动开录区间」内（滑块范围开始前 5 分钟到开始后 10 分钟，默认前后各 3 分钟），直接开始录音，课堂标题取日程名；同一节课内再次开始录音自动命名为「课名-1」「课名-2」，不再询问标题。判定只使用本机已缓存的日程，离线可用。停止录音目前只有手动方式，日程下课、运动传感器、关键词等停止方式已预留代码入口，尚未实现。
- 设置页最下方「日志」区块：级别三档 `None` / `Info` / `Debug`（默认 `None` 不记录；`Info` 记录关键事件并自动脱敏凭据；`Debug` 记录全部细节，会记录敏感信息，抓问题后请切回），显示当前日志占用，「清理日志」一键删除全部日志，「导出日志」把主进程与识别进程日志归并为单个 `.log` 经系统分享发出（可能含敏感信息，仅发给开发者排查问题）。

首版不提供账号、云同步、设备内录、独立问答或整堂课二次识别。当前分段采用轻量音量阈值；嘈杂课堂、口音、耗电和实际延迟必须通过真机测试调整。

## 构建

需要 JDK 17 或 21、Android SDK platform 36、Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1 和 Python 3.10+。

```sh
python scripts/bootstrap.py --native
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`。设置 `ANDROID_HOME`，或在未提交的 `local.properties` 中填写 `sdk.dir`。首次准备和构建需要访问 GitHub、Google Maven 和 Maven Central。

`bootstrap.py` 从固定官方发布版本提取 arm64 JNI 库并下载同版本 Kotlin 接口，验证原生包 SHA-256；这些生成文件不进入版本控制。Gradle Wrapper 固定为 8.13，并验证分发包 SHA-256。本机也可运行 `python scripts/bootstrap.py --gradle` 下载独立 Gradle。

KaTeX 静态文件已经随源码保存。重新获取时运行 `python scripts/fetch_math_assets.py`，脚本核对 npm 发布的完整性校验值。

GitHub Actions 自动执行核心测试、Android 静态检查和构建。每个发布版本都在 [GitHub Releases](https://github.com/nahanhhan/RecNote/releases) 提供 APK；标签 `v<versionName>` 触发验证、体积优化与自动发布。新的优化包沿用仓库固定签名。调试包也可在成功工作流的 `lecture-recording-debug` 产物中下载。

## 私有签名

个人长期安装使用自己的固定签名，后续更新保持同一密钥。在项目根目录创建未提交的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/lecture-release.jks
storePassword=YOUR_LOCAL_PASSWORD
keyAlias=lecture
keyPassword=YOUR_LOCAL_PASSWORD
```

运行 `./gradlew :app:assembleRelease`。密钥与密码必须另行备份，不上传仓库；丢失密钥后无法覆盖安装已有版本。调试 APK 使用开发调试签名，不能覆盖不同签名的正式 APK。

也可以运行 `python scripts/create_signing_key.py` 生成固定本地密钥和配置。脚本保留已有签名配置，密码不输出到终端；将 `.tools/signing/lecture-release.jks` 和根目录 `keystore.properties` 一起私下备份。

## 手机上使用

1. 安装 arm64 APK，在设置中下载默认识别模型并确认安装完成。
2. 检查手机后台/电池设置，开始课堂录音，按需暂停、继续和拍照。
3. 结束后等待剩余实时段落完成。模型未准备好时音频仍保存，之后只处理未完成短段。
4. 在设置中选择云端供应商，填写模型名称和它提供的 API Key，再测试连接。只整理文字时关闭「让模型读取照片内容」；读图还需所选模型支持图片。
5. 核对原稿和照片，手动整理笔记，完成后编辑、回听和导出。

如果有课表，可在设置的「课程日程」导入 `.ics` 文件或添加订阅，并调整自动开录区间；之后在课前或迟到几分钟内从桌面打开应用即自动开录。

首页「导入音频文件」支持 MP3、AAC（ADTS）、WAV（PCM）和 M4A（常见 AAC 编码）。实际编码需设备支持，单文件最多 4 GB、24 小时，转换与转写期间需足够本机空间。导入记录只整理文字，不进入拍照流程。

横屏拍照跟随设备方向；缩略图、模型输入与浏览器阅读遵循照片方向信息，原图保留。

普通锁屏与切换页面由前台服务维持；系统强制停止、关机或权限撤销属于中断。再次打开会修复已写入 WAV 文件头、保留未完成任务，并由用户继续操作。

## 云端供应商

| 入口 | 默认基础地址 | 使用说明 |
| --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | `/v1` 地址也可手动填写；使用非思考模式的工具调用，不发送 `strict` 或 `parallel_tool_calls`。模型能力以实际测试为准。 |
| OpenRouter | `https://openrouter.ai/api/v1` | 模型名一般为 `供应商/模型`；模型列表提供能力信息时显示读图和工具调用提示。 |
| OpenCode Go | `https://opencode.ai/zen/go/v1` | Go / Go Plus 套餐；使用 Go 的密钥、Chat Completions 模型，不加 `opencode-go/` 前缀。带真实应用身份和稳定会话编号。官方面向编程代理，课堂整理能否使用需实际账号测试。 |
| OpenCode Zen | `https://opencode.ai/zen/v1` | 使用 Zen API Key；模型名不加 `opencode/`。仅接入其 Chat Completions 模型；Responses、Anthropic、Gemini 专用端点暂不支持。 |
| OpenAI | `https://api.openai.com/v1` | 选择支持 Chat Completions 工具调用的模型。仅支持 Responses 的模型暂不支持。 |
| 自定义 | 手动填写 | 支持 HTTPS Chat Completions，保留网关路径；不自动猜测或添加 `/v1`。 |

供应商切换不会把上一家的密钥带到另一家。修改地址、模型、密钥、照片或严格模式后，需要重新测试；旧版配置会迁移并要求重新测试。恢复旧图文任务时必须启用已测试的读图配置，避免在文字模式下继续上传照片。

官方接口说明：[DeepSeek](https://api-docs.deepseek.com/)、[DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[OpenRouter](https://openrouter.ai/docs/quickstart)、[OpenCode Zen](https://opencode.ai/docs/zen/)、[OpenCode Go](https://opencode.ai/docs/go/)。

## 工程结构

| 路径 | 职责 |
| --- | --- |
| `app/` | 页面、录音、CameraX、Room、独立识别进程、下载、云端及导出 |
| `core/` | 时间/文件名规则、分段、分批、笔记校验、接口协议及 JVM 测试 |
| `docs/openai-tool-calling/` | 工具定义与完整离线往返示例 |
| `docs/ARCHITECTURE.md` | 数据与任务实现细节 |
| `scripts/tests/` | 端侧自检（uv 单命令）与脚本测试 |
| `scripts/` | 固定依赖准备脚本 |
| `app/src/main/cpp/` | 官方 libbzip2 C 源码及小型 C++ 桥接，用于模型解压 |

## 第三方来源

- [sherpa-onnx 1.12.27](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.27)，Apache-2.0，许可证保存在 `third_party/`。
- [FireRedASR 模型说明](https://k2-fsa.github.io/sherpa/onnx/FireRedAsr/pretrained.html)，模型在用户设备按需下载。
- [KaTeX 0.16.22](https://github.com/KaTeX/KaTeX/releases/tag/v0.16.22)，MIT，许可证保存在 `third_party/`。
- [libbzip2 1.0.8](https://sourceware.org/bzip2/)，官方源码随工程保存，用于原生模型解压；源码包 SHA-256 和许可证保存在工程中。
- [OpenAI Function Calling](https://developers.openai.com/api/docs/guides/function-calling)，本应用实现现有方案指定的 Chat Completions 适配器；所选服务和模型必须支持该端点。
