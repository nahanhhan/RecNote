# Spec Delta

## Purpose

定义端侧提交前自检的持久行为：一条只读命令完成静态检查与逻辑校验，不要求 Kotlin/Android 环境；检查与验证脚本以 `scripts/tests/` 为仓库唯一 uv 项目源。

## ADDED Requirements

### Requirement: 端侧单命令自检

仓库 SHALL 提供端侧提交前自检入口：在 `scripts/tests` 下执行 `uv run python -m checks` 单命令完成全部静态检查与逻辑校验。检查 MUST 只读（不修改被检查文件）；失败时以非零退出码退出并逐项列出失败检查与涉及文件；执行环境 SHALL 仅需 Python 与 uv，MUST NOT 要求 Kotlin/JDK/Android SDK。

#### Scenario: 全部通过

- **WHEN** 开发者在 `scripts/tests` 下执行 `uv run python -m checks` 且所有检查通过
- **THEN** 输出逐项通过摘要与通过总数，退出码为 0

#### Scenario: 存在失败

- **WHEN** 任一检查发现问题
- **THEN** 退出码非 0，输出列出失败检查名称与具体失败项（含文件位置），被检查文件内容保持不变

#### Scenario: 无 Android 环境可完整执行

- **WHEN** 在未安装 Kotlin/JDK/Android SDK 的机器上执行该命令
- **THEN** 全部检查完整执行并给出通过/失败结论

### Requirement: scripts/tests 为唯一 uv 源

静态检查、逻辑校验与日后新增的验证脚本 SHALL 全部存放于 `scripts/tests/`；仓库内 uv 项目文件（`pyproject.toml`、`uv.lock`）SHALL 只存在于该目录。端侧 Python 验证的执行 SHALL 一律经 uv（`uv run python ...`），MUST NOT 直接以系统 Python 运行。新增验证测试 SHALL 在引入它的同一变更内注册进默认检查清单（`uv run python -m checks` 的检查注册表）并纳入逐项输出与退出码语义。网络探测类调研脚本 SHALL 可手动独立运行，且 MUST NOT 注册进默认检查集。

#### Scenario: 新增验证脚本落在唯一源

- **WHEN** 需要新增脚本验证某段逻辑或探测外部依赖
- **THEN** 脚本放入 `scripts/tests/` 并复用同一 uv 项目，MUST NOT 另立顶层目录或新建第二个 uv 项目

#### Scenario: 新测试同批注册进检查清单

- **WHEN** 某个 change 新增一项验证测试
- **THEN** 同一变更内该测试注册进 `uv run python -m checks` 的检查清单并出现在输出摘要中，MUST NOT 以未注册的一次性脚本留存

#### Scenario: 验证一律经 uv 执行

- **WHEN** 在端侧执行任何 Python 验证脚本（含 unittest 测试）
- **THEN** 通过 `uv run python ...` 从 `scripts/tests` 的 uv 项目执行（如 `uv run --project scripts/tests python -m unittest discover -s scripts/tests`），MUST NOT 直接调用系统 Python

#### Scenario: 调研脚本独立手动运行

- **WHEN** 运行网络探测类调研脚本
- **THEN** 以手动方式单独执行（如 `uv run python probe_download_sources.py`），默认自检命令不包含该脚本且其输出不作为检查结论
