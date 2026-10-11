# Tasks

## 1. 协议、路径和持久化队列

- [x] 1.1 实现 ICS 日程名路径映射、路径校验、稳定内容版本和原子上传任务存储；补 core 单测。
- [x] 1.2 实现 WebDAV PROPFIND/MKCOL/PUT/DELETE、严格多状态检查、凭据保护与模拟 HTTP 测试。
- [x] 1.3 CI 工程验证本批编译、单测、lint；本地执行 uv 只读检查与 OpenSpec 官方校验。[基础批次 CI](https://github.com/nahanhhan/RecNote/actions/runs/38121834263) 通过。

## 2. 设置、上传与用户入口

- [x] 2.1 加密保存 WebDAV 配置，增加日程名映射二级页面和返回导航。
- [x] 2.2 接入上传快照、WorkManager 有限重试和冷启动恢复；整理完成、编辑保存触发；详情页状态与手动上传。
- [x] 2.3 关闭/改配置和删除本地记录清理排队任务；保持导入音频仅文字；调整 HTTP 支持与其他网络入口的 HTTPS 边界。
- [x] 2.4 CI 工程验证本批；同步 README、架构、版本、端侧检查注册和发布说明。[完整功能 CI](https://github.com/nahanhhan/RecNote/actions/runs/38122535437) 通过。

## 3. 上传与发布

- [x] 3.1 上传前检查远端并安全合入 main；功能提交 `20bae5e` 及发布提交 `eb24cba` 的 CI 工程验证通过。[发布提交 CI](https://github.com/nahanhhan/RecNote/actions/runs/38122887105)。
- [x] 3.2 发布 `v0.1.7-alpha`，确认 Releases 有版本相符的 APK，保留历史资源。[发布工作流](https://github.com/nahanhhan/RecNote/actions/runs/38123000109)、[Release](https://github.com/nahanhhan/RecNote/releases/tag/v0.1.7-alpha)。
- [x] 3.3 补齐 `v0.1.6-alpha` 的 APK，按原始 `c8d992a` 独立构建发布，保留已有历史资源。[历史包工作流](https://github.com/nahanhhan/RecNote/actions/runs/38122750135)、[Release](https://github.com/nahanhhan/RecNote/releases/tag/v0.1.6-alpha)。

## 4. 真机验收（人工勾选）

- [ ] 4.1 连接真实 OpenList/其他 WebDAV，保存后重启配置保留，密码不以明文出现在日志或导出。
- [ ] 4.2 导入真实 ICS，新建/修改/删除名称映射，按原始日程名归档；带 -1/-2 标题也归入同文件夹；未匹配和导入音频落到根目录。
- [ ] 4.3 整理带照片笔记后自动上传，在网盘打开 Markdown 与照片正常；音频原件与 API 密钥不上传。
- [ ] 4.4 断网、恢复网络、进程重启、账号错误、空间不足后状态可读并可重试；本地笔记保持可用。
- [ ] 4.5 编辑笔记得到新云端版本，相同内容重复重试路径不变；关闭同步停止队列，本地删除不删除网盘副本。
- [ ] 4.6 系统返回、顶栏返回和旋转均保持二级页面正常；HTTP 局域网网盘与 HTTPS 网盘均可连接。

Android 仪器测试 `WebDavSettingsTest` 覆盖真实 Keystore 密文、配置重读、配置 revision 与映射去重，要求隔离 `.uitest` 应用；本批尚未执行仪器测试，不能以 CI 单元测试通过替代 4.1–4.6。

## 验证记录

- 新增 5 个 core 用例：日程名路由、路径越界/去重、稳定内容版本、重启恢复与旧 worker 更新保护、删除快照隔离。
- 新增 8 个 MockWebServer 用例：目录/随机空探测文件读写、207 属性拒绝、错误 href/外部实体、跳转凭据保护、405 文件冲突、Unicode 根内路径和流式上传、错误脱敏/重试分类、地址凭据与查询参数拒绝。
- 端侧 `uv run python -m checks` 六项通过，新增 WebDAV 安全检查已注册；`uv run python -m unittest discover -s .` 六项发布脚本测试通过，临时目录位于工作区。
- 官方 `openspec validate add-webdav-note-sync --strict` 与同批调整的日程 change 校验通过。
- GitHub 两个功能批次均完成 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，上述链接仅代表工程验证通过。
- 全部测试使用合成数据、模拟 HTTP 服务和假账号，不使用或打印真实凭据；尚未连接真实网盘或执行手机人工验收。
- 两个发布工作流均通过 compact 单测、lint 与优化 APK 构建；下载安装包只作静态核对，未安装到手机。
- `0.1.7-alpha` APK：27,578,593 字节，SHA-256 `4cf4d481c67547acb0ba8e427faabe1fdb7abec0245625ab773f54006413becd`，实际包名 `io.github.nahanhhan.lecturerecording`、versionCode 6、versionName `0.1.7-alpha`、arm64-v8a。
- `0.1.6-alpha` APK：27,513,057 字节，SHA-256 `d5501c4aca8d6e39e2410713241b9dbd5fd40339f120a8449a236bd53a66bd01`，实际 versionCode 5、versionName `0.1.6-alpha`、arm64-v8a；标签准确指向 `c8d992a`。
- 两包签名均经 apksigner 验证，公开证书 SHA-256 为 `4149018b4ba6e74ff27c12e96d775fe01bbab4b510275509c1a7225a89f81f3e`，与此前仓库固定签名一致；文件摘要与 GitHub Release 资源摘要一致。
