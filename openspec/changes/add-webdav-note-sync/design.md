# Design

## Context

当前 `LessonEntity` 已保存 `scheduleTitle`，笔记批次在 Room 中，`NotesRenderer` 输出 Markdown 和相对照片路径。`NotesService` 完成后清除编辑覆盖，详情页可保存编辑笔记。应用已有 WorkManager 和 OkHttp。当前应用禁用明文流量，用户提供的本机 OpenList 示例却是 HTTP。

## Goals / Non-Goals

目标是生成成功后可靠上传、按原始日程名归档、失败可恢复，并保持本地资料可用。只做单向笔记上传；不实现同步录音、下载、远端删除或账号系统。

## Decisions

1. 设置复用 `SettingsStore`，用户名和密码均以现有 AndroidKeyStore AES-GCM 加密保存。地址只能是 HTTP/HTTPS URL，不接受 URL 内嵌凭据、查询参数或 fragment；账号/密码改变时配置 revision 递增。旧任务绑定 revision，配置变化后暂停，用户手动重新上传时使用新配置。
2. 映射以原始 ICS 日程名精确匹配（仅去除首尾空白），禁止重复名称。文件夹路径以 WebDAV 地址为根，`/课程/数学` 与 `课程/数学` 等价；不解释为服务器根路径。禁止 `.`、`..`、反斜杠和控制字符，Unicode/空格/百分号按路径段编码。映射在生成上传快照时确定，后续修改不悄悄迁移已排队内容。
3. 每次上传先在应用私有目录制作不可变 Markdown/照片快照，再原子保存任务文件。目录为 `<映射目录>/<创建时间>_<安全标题>_<录音id>_<内容hash>/notes.md`；照片在相同目录，保持 Markdown 相对链接。hash 包含 Markdown 和照片内容；重复上传相同快照路径稳定，不同版本独立，旧重试不能覆盖新版本。
4. 使用每条录音一个唯一 WorkManager 任务，网络连接约束和指数退避，最多五次自动尝试；新快照替换同录音的旧队列。状态文件不含凭据，冷启动恢复 pending/uploading 为可排队状态；失败/成功记录供设置与详情页展示。关闭同步或改变账号时取消队列；本地删除取消任务并清理上传快照，云端副本保留。
5. 客户端使用 `PROPFIND Depth:0` 验证目标为可读集合，逐级 `MKCOL`（405 必须再次验证确为目录），逐个 PUT 照片，最后 PUT Markdown。成功码逐项检查；207 必须解析对应资源的 propstat，不能仅把 HTTP 207 当成功。限制 XML 大小并拒绝 DTD/ENTITY。连接测试用随机空文件 PUT/DELETE 验证读写权限，不上传课堂资料。
6. 请求禁止自动重定向，不把 Authorization 发给跳转后的地址；错误使用固定可读文案与状态码，不显示/记录响应正文、地址或凭据。TLS 使用系统证书校验。HTTP 为手动配置网盘提供支持，并在表单提示使用 HTTPS；应用网络策略允许明文，但模型和日程入口仍严格 HTTPS，模型下载禁止降级跳转。
7. 上传触发独立于整理任务成功状态。上传排队失败仅显示同步失败，不将已完成整理任务改为等待。手动上传只接受完整完成的笔记，编辑保存后排队最新文字，照片快照保持不变。导入音频不带照片。
8. 设置页面二级导航支持系统返回与顶栏返回，重建后保存所在页面；映射支持新增/编辑/删除确认。连接测试只测试已保存配置。上传历史不自动补传，用户可从详情页主动上传。

## Risks / Trade-offs

- HTTP 不加密，只有用户明确填写 HTTP 才使用，界面提示优先 HTTPS；`127.0.0.1` 指手机本身，电脑上的服务填写电脑局域网地址。
- 不同内容版本占用更多网盘空间，换取避免重试覆盖；本轮不自动清理云端历史。
- WebDAV 多文件上传没有事务：先照片后 Markdown 降低出现正文链接缺失的概率；中途失败保留队列原样重试。
- 厂商后台限制可能延迟上传，WorkManager 等待网络恢复；详情页仍可查看本地笔记与同步状态。

## Migration Plan

设置默认空，升级不自动上传旧资料；不改变现有 Room schema。`0.1.7-alpha` 增加 versionCode 为 6，保留固定签名和历史 Release 资源。

## Verification

路径/映射/内容版本与持久化队列在 core 单测中验证；HTTP 成功、多状态错误、重定向、认证、目录冲突和重试使用 MockWebServer。端侧 `uv run python -m checks` 同批注册新增验证；OpenSpec 用官方 CLI 校验。编译、单测、lint、APK 构建以 GitHub CI 结果为准，真机验收单列在 tasks.md。

参考：[WebDAV RFC 4918](https://datatracker.ietf.org/doc/html/rfc4918)、[Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config)。
