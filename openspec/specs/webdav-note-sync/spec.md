# webdav-note-sync Specification

## Purpose

TBD - created by archiving change add-webdav-note-sync. Update Purpose after archive.

## Requirements

### Requirement: WebDAV 配置与连接测试

设置 SHALL 提供「WebDAV 云同步」地址、用户名、密码与保存/连接测试入口。地址 placeholder MUST 为 `http://127.0.0.1:5244/dav`，实际默认值 MUST 为空。保存非空 HTTP/HTTPS 地址启用同步，保存空地址关闭同步。凭据 MUST 本机加密保存，任何日志档位 MUST NOT 打印新增网盘凭据。地址 MUST NOT 包含内嵌账号、查询串或 fragment。连接测试 MUST 校验 WebDAV 集合及随机测试文件的写入/删除，MUST NOT 发送课堂资料。

#### Scenario: 默认关闭

- **WHEN** 用户首次打开云同步设置
- **THEN** 输入框为空且有示例提示，不上传任何笔记

#### Scenario: 保存并测试

- **WHEN** 用户保存有效网盘地址及账号并测试
- **THEN** 系统验证连接和读写权限，显示结果，密码重启后仍可使用且不可明文导出

### Requirement: 完整笔记自动上传

整理成功或用户保存编辑笔记后，若同步已启用，系统 SHALL 排队上传完整 Markdown 及其本地照片快照。导入音频 MUST 仅上传文字笔记。旧笔记 SHALL 支持详情页手动上传。照片 MUST 先于 Markdown 上传并保持相对链接；音频、模型、API 密钥 MUST NOT 上传。

#### Scenario: 自动上传图文笔记

- **WHEN** 用户完成一份带本地照片的笔记整理且已保存网盘地址
- **THEN** 自动上传笔记和照片，网盘中的 Markdown 可正常引用照片

#### Scenario: 导入音频

- **WHEN** 导入音频完成文字整理
- **THEN** 只排队文字笔记，不追加照片、录音或图文整理

### Requirement: 稳定版本与独立失败

每份笔记 SHALL 使用独立网盘文件夹；相同快照重复上传 MUST 使用相同路径，不同内容 MUST 使用不同版本路径，旧任务 MUST NOT 覆盖新版内容。同步失败 MUST 保留本地笔记和已完成整理状态，并显示失败原因与重试入口。

#### Scenario: 旧任务重试

- **WHEN** 用户编辑后生成新笔记版本而旧任务曾失败
- **THEN** 旧快照与新快照的云端路径不同，新版不会被旧重试覆盖

#### Scenario: 上传失败

- **WHEN** 网盘拒绝上传或网络中断
- **THEN** 本地笔记仍可阅读、编辑和导出，同步失败独立展示

### Requirement: 持久恢复与队列生命周期

系统 SHALL 持久化不含凭据的上传快照与状态，在网络可用时后台执行；暂时性网络错误 SHALL 指数退避并最多自动尝试五次，账号/路径/权限错误停止自动重试，用户 SHALL 可手动重试。关闭同步或改变账号配置 MUST 取消旧队列，MUST NOT 悄悄发送到新账号。删除本地录音 MUST 取消并清除其队列与本地快照，MUST NOT 删除云端副本。

#### Scenario: 中断后恢复

- **WHEN** 上传未结束时进程重启且配置保持不变
- **THEN** 待上传快照恢复排队，成功后显示已上传

#### Scenario: 改配置或删除

- **WHEN** 用户关闭同步、改变地址/账号或删除本地记录
- **THEN** 对应旧队列停止，云端既有文件保持

### Requirement: HTTP 与响应安全边界

客户端 SHALL 支持用户填写的 HTTP/HTTPS WebDAV，界面 SHALL 提示 HTTPS 和本机地址含义；TLS MUST 校验证书，请求 MUST NOT 自动跟随重定向。模型和 ICS 订阅 MUST 继续强制 HTTPS，模型下载 MUST NOT 降级到 HTTP。PROPFIND 的 207 MUST 核对对应资源和成功的集合属性，MKCOL 的 405 MUST 验证目录存在，XML MUST 限制大小并禁止外部实体。

#### Scenario: 跳转与多状态失败

- **WHEN** 服务返回重定向或 207 内对应资源权限失败
- **THEN** 显示可读错误，不跟随跳转发送账号，不误报连接成功
