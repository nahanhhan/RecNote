## MODIFIED Requirements

### Requirement: WebDAV 配置与连接测试

设置 SHALL 提供「WebDAV 云同步」独立启用开关、地址、用户名、密码与保存/连接测试入口。地址 placeholder MUST 为 `http://127.0.0.1:5244/dav`，实际默认值 MUST 为空。同步仅在开关启用且保存地址非空时运行；关闭开关 SHALL 保留地址和凭据。旧用户已保存地址 SHALL 延续既有启用行为。凭据 MUST 本机加密保存，任何日志档位 MUST NOT 打印新增网盘凭据。地址 MUST NOT 包含内嵌账号、查询串或 fragment。连接测试 MUST 校验 WebDAV 集合及随机测试文件的写入/删除，MUST NOT 发送课堂资料。

#### Scenario: 默认关闭

- **WHEN** 用户首次打开云同步设置
- **THEN** 输入框为空且有示例提示，不上传任何笔记

#### Scenario: 保存并测试

- **WHEN** 用户保存有效网盘地址及账号并测试
- **THEN** 系统验证连接和读写权限，显示结果，密码重启后仍可使用且不可明文导出

#### Scenario: 关闭但保留配置

- **WHEN** 用户关闭同步开关
- **THEN** 待上传和后续自动上传停止，地址和加密凭据保留

### Requirement: 完整笔记自动上传

整理成功或用户保存编辑笔记后，若同步已启用，系统 SHALL 排队上传同一快照的完整 PDF、Markdown 及其本地照片到同一网盘目录。导入音频 MUST 仅上传文字 PDF/Markdown。旧笔记 SHALL 支持详情页手动上传。照片和 PDF MUST 先于 Markdown 上传并保持相对链接；音频、模型、API 密钥 MUST NOT 上传。PDF 生成失败 MUST 独立显示同步失败，不改变已完成整理状态。

#### Scenario: 自动上传图文笔记

- **WHEN** 用户完成一份带本地照片的笔记整理且云同步已启用
- **THEN** PDF、Markdown、照片自动上传至同一目录，Markdown 可正常引用照片

#### Scenario: 导入音频

- **WHEN** 导入音频完成文字整理
- **THEN** 只排队文字 Markdown/PDF，不追加照片、录音或图文整理
