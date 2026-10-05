# Proposal

## Why

CI 在全新 runner 上执行 `:app:assembleDebug` 时，Android Gradle Plugin 自动生成一次性 `~/.android/debug.keystore`，导致每次 CI 产物的签名证书都不同。用户下载新产物后无法覆盖安装（签名冲突），只能卸载重装、丢失本地数据，即"每次产物无法平滑升级"。需要一个固定的静态 debug 签名，使历次产物签名一致、可平滑覆盖安装。

## What Changes

- 新增独立初始化工作流 [`init_debug_keystore.yml`](.github/workflows/init_debug_keystore.yml)（手动触发，与 CI 构建工作流分离）：在项目根目录一次性生成静态 `debug.keystore` 并提交入库；根目录已存在该文件时 MUST NOT 替换（替换会打断升级链）。
- [`app/build.gradle.kts`](app/build.gradle.kts) 为 debug **显式配置静态签名**（指向根目录 `debug.keystore`）；[`android.yml`](.github/workflows/android.yml) 移除隐式加载步骤、保留缺失即显式失败守卫；MUST NOT 回退到构建机自动生成密钥。
- 配套仓库调整：[`.gitignore`](.gitignore) 例外放行根目录 `debug.keystore`，[`.gitattributes`](.gitattributes) 将 keystore 标记为二进制，防止 `* text=auto` 换行转换损坏文件。
- **BREAKING**（一次性）：已按旧 debug 签名安装的设备需卸载重装一次；此后产物间可平滑升级。release 私有签名流程不受影响。

## Capabilities

### New Capabilities
- `app-signing`: 构建产物的签名行为——debug 构建始终使用项目根目录的静态 `debug.keystore`（一次性初始化、不可替换、缺失即失败），保证历次产物签名一致且可平滑覆盖安装；release 构建继续使用本地私有密钥，静态 debug 密钥 MUST NOT 用于 release。

### Modified Capabilities

（无；`ci-verification` 的验证命令与产物上传行为不变，签名稳定性由 `app-signing` 约束构建产物本身。）

## Impact

- **代码/配置**：[`app/build.gradle.kts`](app/build.gradle.kts)（debug 显式签名配置）、[`android.yml`](.github/workflows/android.yml)、新增 [`init_debug_keystore.yml`](.github/workflows/init_debug_keystore.yml)、[`.gitignore`](.gitignore)、[`.gitattributes`](.gitattributes)
- **新文件**：`debug.keystore`（项目根目录；调试专用，口令为公开约定值，不承载任何发布信任）
- **不变**：release 签名流程（根目录 `keystore.properties`、[`scripts/create_signing_key.py`](scripts/create_signing_key.py)、`.tools/signing/` 私有密钥）完全不动
- **范围假设**：debug 构建（CI 与本地）统一使用根目录静态 `debug.keystore`，本地与 CI debug 产物互相可覆盖安装
- **兼容性**：旧 debug 签名安装需一次性卸载重装；此后连续 CI 产物之间可覆盖安装升级
