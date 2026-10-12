# 验证记录

## 来源与范围

2026-10-12，功能基于云端 main 的 48fa401 实施，保留此前成员提交及已归档规范。每批提交上传前 fetch 并检查云端 main 是本地提交的祖先；没有强制推送。版本为 0.1.9-alpha，versionCode 8，Room 6。

## 工程与运行验证

- `uv run python -m checks`：7 项统一检查全部通过。
- 官方 `openspec validate improve-mobile-note-workflow --strict`：通过。
- [完整 CI：38207413449](https://github.com/nahanhhan/RecNote/actions/runs/38207413449)，提交 `29e0d34fe04dee537c599841e12d15b3fd73b886`：核心及 Android 单元测试、仪器测试源码编译、lint、debug APK 构建通过。
- 隔离 Android 15 / x86_64 模拟器实际执行 10 项测试全部通过：PDF 生成、Room 任务恢复及用户暂停、相机全尺寸文件回传/取消、模型删除互斥、WebDAV 设置和数据库迁移。
- 测试应用使用 `.uitest` 独立包名，只处理合成数据，不操作用户手机或真实网盘。原生相机测试验证文件回传协议，不代表实际手机相机应用已经验收。

## PDF 样例

运行产物 `isolated-runtime-reports`（资产 11727379579）下载 SHA256 与 GitHub 元数据一致：`12f3a1baaf3258136369abe5f9de03897c8a31c7c6482638cfa35eb9120a4ddf`。

合成样例共 4 页 A4，逐页 PNG 检查中文标题、正文、离线公式 E=mc²、照片、表格、页码：内容可见，没有截断或重叠。PDF 文字提取确认中文可选取，完整包含第 1 至第 100 段。CI 采用 MediaStore 的共享 Download 目录保存样例，避免测试后卸载应用清除产物。

## APK 发布

- [发布 CI：38208299584](https://github.com/nahanhhan/RecNote/actions/runs/38208299584)：核心/Android 单元测试、compact lint、优化 APK 构建及上传全部通过。
- [v0.1.9-alpha 发布页](https://github.com/nahanhhan/RecNote/releases/tag/v0.1.9-alpha)，保留既有版本；新标签指向经过完整验证的 `29e0d34fe04dee537c599841e12d15b3fd73b886`。
- 已实际下载 `recnote-0.1.9-alpha.apk`，26742585 字节；SHA256 与 GitHub 资产元数据一致：`857648962baad626bc46d8405e12e0fe0b9d490bcbe40395a5c1b72493b55f65`。
- 本地仅只读检查 APK：应用 ID `io.github.nahanhhan.lecturerecording`，版本名 `0.1.9-alpha`，versionCode `8`，ABI `arm64-v8a`，无 debuggable 标记。
- APK 签名验证通过，证书 SHA256 沿用固定值 `4149018b4ba6e74ff27c12e96d775fe01bbab4b510275509c1a7225a89f81f3e`。没有在本地编译或运行 Android 应用。

## 尚需真机与真实服务验收

tasks.md 的 4.2 至 4.5 保持未勾选：真实手机长时间锁屏/系统省电/OEM 相机及麦克风持续录音、模型实际识别和真实 WebDAV 上传。模拟器和模拟 HTTP 测试不能替代这些结果。系统强制停止期间不能继续执行，再次打开会恢复待整理任务；用户主动暂停的任务不会自动启动。
