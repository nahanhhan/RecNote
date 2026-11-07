# Spec Delta

## Purpose

为 RecNote 提供可持久保存的本地与远程算力选择，使用户能将录音识别交给电脑来降低手机计算负担，同时保留已有模型、整理配置和历史资料，并清楚区分每份记录实际采用的处理方式。

## ADDED Requirements

### Requirement: 远程模式诊断日志

RecNote SHALL 通过现有日志导出功能记录电脑健康检查、双端探测各阶段、云盘传输和电脑识别状态。Info 与 Debug 日志 MUST 能用脱敏编号关联同一次探测或同一任务，区分电脑读取失败与手机取回执失败；Debug 还记录各阶段开始。日志 MUST 使用固定事件、状态、受限错误码与数值，MUST NOT 含账号、凭据、实际地址、路径、服务端原文、音频或原稿正文。

#### Scenario: 电脑确认写入后手机取回执失败
- **WHEN** 电脑探测成功但手机读取回执收到 HTTP 404
- **THEN** 导出日志按同一个探测编号记录电脑探测结果及手机回执下载 404，界面指出失败发生在手机读取阶段

### Requirement: 设置页顶部切换处理模式

设置页 SHALL 在所有设置区块之前提供「本地模式 / 远程模式」，新安装与升级未选择模式的用户 MUST 默认本地模式。远程模式 MUST 隐藏本地识别模型区块，改为 ASR 后端 URL、token、保存和连接测试；切回本地 MUST 恢复模型区块及保存的配置。手机云端笔记整理区块 MUST 在两种模式均可使用，包含供应商预设、模型、密钥、读图和系统提示词。模式 SHALL 重启后保留，课程日程、笔记 WebDAV 同步与日志设置 SHALL 继续可用。

#### Scenario: 默认与切换
- **WHEN** 用户升级后首次打开设置，再选择远程模式并重启
- **THEN** 初始为本地模式，重启后为远程模式，本地模型区块隐藏，ASR 后端输入区显示，手机云端整理设置仍可使用

#### Scenario: 切回本地
- **WHEN** 用户切回本地模式
- **THEN** 原模型区块恢复显示，供应商预设、密钥与提示词继续保持原配置，远程配置保留而不会转换历史记录

### Requirement: 远程连接配置与测试

后端 URL SHALL 支持用户指定的 HTTP/HTTPS 基础地址，默认 MUST 为空，不允许内嵌账号、查询串或 fragment。远程 token MUST 加密保存、输入时遮蔽且任何日志档位均不得输出。ASR 连接测试 SHALL 检查协议版本、后端识别就绪状态以及双端云盘目录可达性，MUST 仅使用合成小型探测资料，不发起真实识别或笔记整理；手机的 LLM 连接测试 SHALL 沿用原流程，两个测试状态 MUST 相互独立。health SHALL 检查 `storage_ready`；probe 请求 SHALL 使用 `protocol_version=1, token, path, sha256`，path MUST 为相对云盘根且恰好三段的 `RecNoteRemote/_probe/<uuid>.bin`。响应 SHALL 读取 `protocol_version, ok, asr_ready, model_loaded, read_ok, write_ok, receipt{path, sha256}, error`，以响应布尔值判断就绪，不能仅看 HTTP 成功。手机 MUST 回读 `<探测路径>.receipt.json` 并将实际回执文件字节摘要与响应 `receipt.sha256` 比对，再核对回执内探测文件校验值和本次探测一致；所有检查通过才可报告双端目录可达。`model_loaded=false` SHALL 提示首块识别可能较慢，不单独判定连接失败；`ok=false` MUST 显示就绪原因且不得进入退避重试。界面 SHALL 说明两个 `127.0.0.1` 分别指各自设备、HTTP 的传输方式及电脑需在线；测试失败 SHALL 展示原因。

#### Scenario: 未配置
- **WHEN** 用户选中远程模式但尚未保存有效后端或手机网盘连接
- **THEN** 开始远程录音或导入前提示缺少的配置，不启动本地识别，不误报连接成功

#### Scenario: 双端路径不一致
- **WHEN** 手机可写入探测文件而电脑不能在对应目录读到它
- **THEN** 测试明确报告目录不可达，不能仅凭后端在线显示全部连接正常

#### Scenario: 电脑可读但无法写回
- **WHEN** 电脑读入手机探测文件但 `write_ok=false` 或手机无法读取有效回执
- **THEN** 手机连接测试失败，不能显示双端目录全部可达

#### Scenario: 回执校验不通过
- **WHEN** 回执路径不是本次 `<探测路径>.receipt.json`，或其原始字节摘要与响应 `receipt.sha256` 不同
- **THEN** 手机拒绝探测成功结果，不访问越界路径、不认可其他探测的回执

#### Scenario: 模型尚未加载
- **WHEN** 协议、就绪、双向读写及回执校验均通过，仅 `model_loaded=false`
- **THEN** 连接测试可成功并提示首次识别可能较慢

#### Scenario: 就绪检查未通过
- **WHEN** probe 返回 HTTP 成功但 `ok=false`
- **THEN** 手机显示未就绪原因，等待用户修正配置，不按暂时性 HTTP 故障自动退避

### Requirement: 每份记录冻结处理模式

新手动录音、日程自动录音和音频导入 SHALL 保存当时的识别模式、ASR 后端配置版本、云盘连接版本及独立任务标识。无模式字段的历史记录 MUST 按本地模式恢复。修改全局识别模式 MUST 只影响新记录；已开始的录音、补传和转写检查 SHALL 继续使用该记录的识别模式。新 ASR 设置 MUST NOT 静默改变旧识别任务的服务器或账号。笔记整理 SHALL 在两种模式下均使用手机既有 LLM 流程，不受 ASR 模式或后端连接版本切换影响。

#### Scenario: 录音中切换模式
- **WHEN** 远程录音已开始后用户切回本地模式
- **THEN** 当前录音仍上传大切片并等待电脑结果，新录音才使用本地模式

#### Scenario: 历史恢复
- **WHEN** 用户打开升级前未完成的本地录音，而全局设置为远程模式
- **THEN** 该记录保持原本地恢复流程，旧音频与笔记保留

### Requirement: 远程模式只在手机封存大切片

远程记录 SHALL 只在手机采集或解码为约 30 秒的完整音频块，不要求本地模型、不启动手机识别进程、不生成本地按句识别临时片段或预览队列。按句切分及识别 MUST 交给 Voice2Note；手机 SHALL 等待每个大切片的完整结果。网络故障 MUST 保留录音文件且不得自动回退到本地识别。

#### Scenario: 未安装模型仍录音
- **WHEN** 用户保存有效远程配置但手机没有识别模型
- **THEN** 可录音并上传完整大切片，手机没有识别模型缺失警告或本地识别活动

#### Scenario: 远程音频导入
- **WHEN** 用户在远程模式导入受支持的音频
- **THEN** 手机按原有格式与容量限制完成解码并封存大切片，电脑按句识别，导入记录保持纯文字且不追加照片

### Requirement: 配置改变与任务生命周期

后端地址／token 或网盘地址／账号改变 SHALL 暂停使用旧连接版本的未完成远程任务，明确显示原配置已改变，MUST NOT 用新配置继续发送旧资料；恢复匹配配置后用户 SHALL 能手动重试。单独关闭笔记同步开关 MUST NOT 关闭已授权的音频中转。进程重启 SHALL 恢复未完成上传和控制通知，但 MUST NOT 自动开始麦克风录音或自动创建新的 LLM 整理任务；用户已经提交的整理任务 SHALL 可恢复其排队与重试。

#### Scenario: 修改连接
- **WHEN** 旧任务等待补传时用户改为另一服务器或网盘账号
- **THEN** 旧任务暂停且本地音频保留，新记录使用新配置，不将旧资料发到新目标

#### Scenario: 关闭笔记同步
- **WHEN** 用户只关闭笔记 WebDAV 同步开关
- **THEN** 笔记同步队列停止，当前远程录音的音频中转仍按原任务继续

### Requirement: 回执内部字段与本次探测绑定

手机 SHALL 按对端 `7dd3e1d` 的回执实现读取 `protocol_version, probe_path, probe_sha256, asr_ready, timestamp`，核对协议版本、本次探测路径与摘要、识别就绪。timestamp MUST 为有效 UTC `YYYY-MM-DDTHH:mm:ssZ`；原始回执文件 SHA-256 MUST 先与控制响应 receipt.sha256 相符。任意其他字段中的相同摘要 MUST NOT 替代 probe_sha256。

#### Scenario: 其他字段碰巧含相同摘要
- **WHEN** 回执中其他字段包含本次摘要但 probe_sha256 不匹配
- **THEN** 手机拒绝本次连接测试成功

#### Scenario: 回执未就绪或时间无效
- **WHEN** 回执 asr_ready 为 false 或 timestamp 格式无效
- **THEN** 手机显示连接测试失败且不启用成功状态
