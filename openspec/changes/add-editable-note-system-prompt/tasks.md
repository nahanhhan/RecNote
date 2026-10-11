# Tasks

## 1. 模板与协议

- [x] 1.1 实现默认/legacy 提示词、变量单次替换和标题选择，补 core 行为单测。
- [x] 1.2 请求接收自定义系统提示词，工具定义移除固定风格限制，示例同步；Debug 日志省略系统消息正文。
- [x] 1.3 本批 CI 工程验证通过，记录链接；OpenSpec 官方校验与 uv 只读检查通过。[基础批次 CI](https://github.com/nahanhhan/RecNote/actions/runs/38128125099)。

## 2. 设置与任务快照

- [x] 2.1 设置增加「系统提示词」按钮与二级编辑页，支持保存、恢复默认、光标插入变量和示例预览，草稿旋转后保留。
- [x] 2.2 接入系统日期/时间格式，创建任务时冻结展开文本，全部批次和恢复使用同一快照；不改变云端接口测试状态。
- [x] 2.3 数据库 3→4 非破坏性迁移，扩展旧版本迁移测试；旧任务沿用 legacy 提示词。
- [x] 2.4 同步 README、架构、版本、发布说明和端侧检查注册；本批 CI 工程验证通过。[完整功能 CI](https://github.com/nahanhhan/RecNote/actions/runs/38128881813)，包括仪器测试源码编译。

## 3. 上传与发布

- [x] 3.1 再次检查远端并正常推送 main，功能提交 `4ddb27a` 及发布提交 `6483395` 工程验证通过。[发布提交 CI](https://github.com/nahanhhan/RecNote/actions/runs/38129419797)。
- [x] 3.2 发布 `v0.1.8-alpha` 并确认 APK 实际版本、签名和摘要，保留历史资源。[发布工作流](https://github.com/nahanhhan/RecNote/actions/runs/38129422800)、[Release](https://github.com/nahanhhan/RecNote/releases/tag/v0.1.8-alpha)。

## 4. 真机验收（人工勾选）

- [ ] 4.1 点击设置「系统提示词」立即进入编辑页；保存重启后保留；取消返回不保存；恢复默认后可预览并保存。
- [ ] 4.2 编辑中旋转草稿保留；系统返回和顶栏返回都回设置；插入变量位于光标/选区。
- [ ] 4.3 切换系统区域、12/24 小时制及时区，预览和新任务日期时间格式随系统；有日程、无日程及导入音频标题均正确。
- [ ] 4.4 使用不同写作风格模板创建真实整理任务，全部批次生效；编辑设置后续跑旧任务风格保持，发起新任务才使用新提示词。
- [ ] 4.5 升级保留原有录音、笔记和等待任务；旧任务继续使用原始提示词；Debug 导出不包含 system 消息正文。

## 验证记录

- 新增 7 个 core 行为测试，通过基础与完整功能 CI：重复/未知变量、特殊字符与非递归替换、原始日程名及用户标题回落、空白/长度校验、旧任务兼容、自定义 system 消息及回执复用、日志省略且不改变实际请求。
- `SystemPromptSettingsTest` 新增 3 个仪器测试，覆盖设置持久化及无效保存、系统时间格式与应用区域独立性、编辑保存/重建/默认恢复；`DatabaseMigrationTest` 覆盖 1→4、2→4 和 3→4 数据保留。源码经 CI 编译通过，尚未在设备执行。
- 端侧 `uv run python -m checks` 七项通过，新增提示词边界检查已注册；发布脚本的六项 `uv run python -m unittest discover -s .` 测试通过。
- 官方 `openspec validate add-editable-note-system-prompt --strict` 通过。
- 未使用真实录音资料或密钥，没有连接真实模型测试自定义风格；第 4 组保持待人工验收。
- 发布工作流的 compact 单测、lint 和优化 APK 构建均通过；下载安装包仅作静态核对，未安装到手机。
- APK 实际包名 `io.github.nahanhhan.lecturerecording`、versionName `0.1.8-alpha`、versionCode 7、arm64-v8a；文件大小 27,594,933 字节。
- APK SHA-256 `356ccb6a410062ef00058521360760327de041bf59d68c72cd918b0bd180e841` 与 GitHub Release 资源摘要一致。
- apksigner 验证通过，公开签名证书 SHA-256 为 `4149018b4ba6e74ff27c12e96d775fe01bbab4b510275509c1a7225a89f81f3e`，沿用仓库固定签名；历史标签和 APK 保留。
- 提示词边界检查补齐文件缺失/JSON 格式错误的失败列表反馈，避免异常终止而遗漏其他检查摘要。
