# Spec Delta

## Purpose

约束构建产物的签名行为：debug 构建始终使用项目根目录的静态 `debug.keystore` 签名，保证历次产物签名一致、可平滑覆盖安装；release 构建继续使用本地私有密钥。

## ADDED Requirements

### Requirement: 静态 debug 签名密钥
项目根目录 SHALL 维护一个固定的 `debug.keystore` 作为 debug 构建的签名密钥；该文件内容 MUST 保持逐字节稳定，替换或重新生成即视为新签名身份。

#### Scenario: 仓库包含静态签名密钥
- **WHEN** 检出仓库并查看项目根目录
- **THEN** 存在 `debug.keystore`，且该文件为一次性初始化生成后入库的固定内容

#### Scenario: 跨平台检出内容一致
- **WHEN** 在启用换行自动转换的平台上检出仓库
- **THEN** `debug.keystore` 内容逐字节一致，可直接用于签名

### Requirement: 签名密钥一次性初始化
系统 SHALL 提供独立于 CI 构建工作流的初始化工作流，在项目根目录创建 `debug.keystore`；文件已存在时 MUST NOT 替换或重新生成。

#### Scenario: 首次初始化创建密钥
- **WHEN** 项目根目录不存在 `debug.keystore` 并运行初始化工作流
- **THEN** 在项目根目录创建新的 `debug.keystore` 并入库

#### Scenario: 已存在时保留既有密钥
- **WHEN** 项目根目录已存在 `debug.keystore` 且再次运行初始化工作流
- **THEN** 既有文件保持不变，不生成新密钥

### Requirement: debug 构建显式使用静态签名
debug 构建 SHALL 由构建脚本显式声明以项目根目录 `debug.keystore` 签名；MUST NOT 依赖构建机默认调试密钥或自动生成的一次性调试密钥，密钥缺失时 MUST 显式失败而非自动回退。

#### Scenario: 全新环境产物使用静态签名
- **WHEN** CI 在全新构建环境运行 debug 构建
- **THEN** 产出 APK 的签名证书与项目根目录 `debug.keystore` 的证书一致

#### Scenario: 密钥缺失显式失败
- **WHEN** 项目根目录缺少 `debug.keystore` 时运行 debug 构建
- **THEN** 构建失败并报告缺少静态签名密钥，不产出 APK

### Requirement: 产物平滑升级
任意两次 debug 构建产物 SHALL 使用同一签名证书，设备可在保留应用数据的前提下覆盖安装较新产物。

#### Scenario: 覆盖安装较新产物
- **WHEN** 设备已安装某次 debug 产物，再安装之后构建的 debug 产物（versionCode 不低于已装版本）
- **THEN** 覆盖安装成功且应用数据保留

#### Scenario: 历次产物签名一致
- **WHEN** 对比任意两次 CI 构建的 debug APK
- **THEN** 两者签名证书指纹相同

### Requirement: release 签名边界
静态 debug 密钥 MUST NOT 用于 release 构建签名；release 构建 SHALL 保持使用本地私有密钥流程。

#### Scenario: release 签名保持私有
- **WHEN** 构建 release 产物
- **THEN** 使用本地私有密钥签名，签名证书与静态 debug 密钥的证书不同
