# Spec Delta

## Purpose

用少量控制通知协调手机录音与电脑识别，在不用公开接口传输录音文件的前提下完成授权、独立任务建立、停止后的可靠收尾以及中断重试，并确保多部手机和多次录音不会误操作彼此任务。

## ADDED Requirements

### Requirement: 网关错误页面提供明确检查方向

电脑控制接口返回非 JSON 的 502/503/504 页面时，手机 SHALL 显示检查电脑识别服务是否启动、域名转发地址及端口的提示，并保留 HTTP 状态码；MUST NOT 回显页面正文或凭据，MUST NOT 因 HTTP 状态自行将无有效结构化错误体的请求标记为可重试。

#### Scenario: 识别服务的转发目标不可达
- **WHEN** 电脑控制请求收到 HTTP 502 的 HTML 错误页面
- **THEN** 手机显示服务／转发检查提示，且不展示笼统的 JSON 格式错误或服务端页面正文

### Requirement: 开始通知立即建立轮询任务

手机 SHALL 在录音或导入的远程任务建立后尽快发送开始通知，包体包含协议版本、连接 token、稳定任务标识和云盘任务相对路径。电脑接受后 SHALL 开始轮询约定目录，不要求首块已上传；重复的相同开始请求 MUST 返回同一任务和同一任务 token。相同任务标识配不同路径 MUST 以 `409 session_id_conflict` 被拒绝；已取消任务再次开始 MUST 返回 `409 session_terminal`。手机 SHALL 对后者显示需新建任务的可读状态，为新录音生成新 `session_id`，MUST NOT 把旧任务反复开始或当作通用异常弹窗。通知失败 MUST 持久排队且不阻止已保存有效配置下的录音采集。

#### Scenario: 第一块尚未产生
- **WHEN** 电脑收到合法开始通知而录音未满 30 秒
- **THEN** 建立任务并等待清单出现，不能因目录暂时为空退出

#### Scenario: 开始响应丢失
- **WHEN** 电脑已接收开始通知而手机未收到响应并重试
- **THEN** 返回原任务与原任务 token，不建立重复识别任务

### Requirement: 每份录音有独立任务 token

连接 token SHALL 用于连接测试和建立授权 ASR 任务；电脑 SHALL 为每个任务签发独立任务 token，用于结束通知、识别状态、重试与取消，不用于 LLM 整理。不同手机共享连接 token 时，任务 token MUST 仍不同。仅有连接 token MUST NOT 能结束任意任务；任务 token MUST 只能操作绑定任务，不允许根据最近一次录音猜测目标。手机 token 与需要重放的电脑任务 token MUST 加密保存，电脑连接鉴权 SHALL 保存不可逆摘要，所有 token MUST NOT 记录到日志或云盘文件。

#### Scenario: 两台手机同时录音
- **WHEN** 两部手机使用相同后端配置同时发起录音
- **THEN** 得到不同任务 token，一部手机结束自己的录音不影响另一部

#### Scenario: token 与任务不匹配
- **WHEN** 查询、重试或取消请求试图用任务甲的 token 操作任务乙
- **THEN** 请求被拒绝且任务乙保持原状态

### Requirement: 结束通知包体只有任务 token

结束通知 SHALL 只携带 `token` 字段，其值为开始响应签发的任务 token。用户停止录音后手机 SHALL 排队该通知，电脑收到后 SHALL 进入收尾阶段，继续轮询最终清单并完成所有声明块的转写与结果回写。只要任务 token 合法，结束通知 MUST 恒返回成功，包括重复结束；手机 MUST NOT 为合法 end 增加 409 冲突分支。结束通知 MUST NOT 自动提交 LLM；最终清单未到或仍有失败块时 MUST NOT 显示全部识别完成。

#### Scenario: 最后一个块未上传
- **WHEN** 用户停止后结束通知先于最后音频块到达
- **THEN** 电脑进入收尾等待，最后一块到达后继续识别，全部结果发布才完成

#### Scenario: 停止发生在开始通知成功之前
- **WHEN** 开始通知尚未成功时用户已经停止录音
- **THEN** 手机先恢复开始请求取得任务 token，再发送排队的结束通知，保留原目录和全部音频

#### Scenario: 全部识别完成
- **WHEN** 电脑已校验最终清单并回写全部块结果
- **THEN** 电脑任务止于识别完成；手机取回完整原稿后由用户决定是否使用手机 LLM 整理

### Requirement: 可恢复的任务与可读状态

控制协议 SHALL 显式版本化。接口 `state` MUST 使用 `waiting_source, receiving, draining, completed, failed, cancelled` 六个值，分别区分等待来源、接收／识别、收尾、识别完成、失败及取消，并报告总块数、已完成块数和可读失败原因。普通等待不是失败；缺失文件或识别失败 MUST 保留原任务及成功结果以便重试。电脑重启 SHALL 恢复任务目录、身份、结束标志与块进度；相同音频内容不得在重试后重复计为新块。

#### Scenario: 电脑重启
- **WHEN** 任务收尾时电脑服务重启
- **THEN** 恢复原任务 token 与已完成块，继续等待未完成文件，不自动整理笔记

#### Scenario: 部分识别失败
- **WHEN** 一块识别失败而其他块已完成
- **THEN** 显示失败块并保留成功结果，重试只补足未完成块，不能误报整次录音完成

### Requirement: 控制请求不泄漏身份

客户端 SHALL 校验 TLS 证书、禁止自动跟随控制请求重定向，并限制响应大小和请求超时。鉴权失败和协议不兼容 MUST 显示原因并停止自动提交对应操作；有效结构化错误的重试决策 MUST 服从 `retryable` 而非仅由 HTTP 状态码推断；`retryable=true` 的失败 SHALL 有限退避，`retryable=false` 停止自动重试。请求体未知字段 SHALL 由电脑静默忽略，必填字段仍必须检查存在与类型；手机 v1 MUST NOT 依赖未列明字段。请求日志 MUST 省略 token、网盘凭据和系统提示词正文。控制连接测试 MUST NOT 建立真实录音任务。

#### Scenario: 接口跳转
- **WHEN** 后端返回指向其他站点的重定向
- **THEN** 不跟随跳转发送 token，显示配置错误

#### Scenario: 不兼容协议
- **WHEN** 后端不支持手机要求的协议版本
- **THEN** 明确提示版本不兼容，不以错误格式继续录音任务通知或提交整理

### Requirement: 取消清理电脑本地并持久确认送达

取消 SHALL 停止任务并清理其电脑本地工作目录，保留云盘资料。对已完成任务取消 SHALL 同样清理并将电脑状态改为 `cancelled`。手机删除可删除的录音时 SHALL 为未完成及已完成任务持久排队取消意图，保留绑定原连接的身份与加密任务 token，直到电脑确认或用户明确处理；本地删除后取消发件箱 SHALL 仍可恢复发送，MUST NOT 随录音行一起丢失。配置改变 SHALL 暂停旧取消请求，不向新目标发送旧凭据。

#### Scenario: 已完成任务取消
- **WHEN** 手机删除已完成远程录音并送达合法取消请求
- **THEN** 电脑清理任务工作目录并变为 `cancelled`，云盘资料保留，手机录音不会被回执重建

#### Scenario: 删除时断网
- **WHEN** 用户删除录音时取消请求尚未得到确认
- **THEN** 手机保留独立的取消待发送记录，网络恢复且原配置匹配时继续发送，确认后清理待发送记录

#### Scenario: 已取消任务重复开始
- **WHEN** 对已取消任务重发相同 `session_id` 的 start 得到 `409 session_terminal`
- **THEN** 手机停止旧开始请求的自动重试并提示使用新任务，不显示通用异常弹窗

### Requirement: 定稿错误码与可重试性

手机 SHALL 识别以下接口层错误码：`invalid_request, invalid_cloud_path, invalid_path, unauthorized, forbidden, unknown_session, not_found, session_id_conflict, session_terminal, payload_too_large, unsupported_protocol_version, too_many_requests, internal_error, asr_not_ready, storage_not_ready`。

云盘／处理错误码 SHALL 包括 `audio_missing, audio_sha256_mismatch, audio_format_invalid, audio_too_large, audio_decode_failed, asr_failed, transcript_upload_failed, manifest_invalid, probe_missing, probe_sha256_mismatch, probe_too_large`。错误展示 SHALL 脱敏并区分故障类别；自动重试 MUST 使用响应 `retryable`，`audio_too_large` MUST 为不可重试。缺失或无法解析的错误体 SHALL 保留任务并提示人工检查，不自行猜测可重试性。

#### Scenario: HTTP 状态与可重试标志不等价
- **WHEN** 收到可解析错误体且 `retryable=false`，即使 HTTP 状态为 5xx
- **THEN** 手机不自动重试该失败项，保留音频并显示对应错误类别

#### Scenario: 未知可选字段
- **WHEN** 请求体带有 v1 未识别的附加字段，但必填字段存在且类型正确
- **THEN** 电脑忽略附加字段并按 v1 处理，手机不依赖附加字段产生的行为
