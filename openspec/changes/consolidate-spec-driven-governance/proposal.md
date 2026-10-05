# Proposal

## Why

仓库并存两套 spec-driven 体系：一套是 [`openspec/`](../../../openspec)（以 change 为单位，规范写在 [`config.yaml`](../../../openspec/config.yaml)）；另一套是 codex 时代遗留的 [`IMPLEMENTATION_PLAN.md`](../../../IMPLEMENTATION_PLAN.md) + [`verification/`](../../../verification) 报告目录 + [`AGENTS.md`](../../../AGENTS.md)，三者与 OpenSpec 规矩互相冗余，README 还把独立约定文档当作"完整产品约定"的权威入口。v2 大规模改动即将开始，先统一治理：从今往后以 openspec 为唯一 spec-driven 工具，清除残余，防止新工作继续向旧体系扩散。

## What Changes

- **BREAKING** 删除 [`IMPLEMENTATION_PLAN.md`](../../../IMPLEMENTATION_PLAN.md)：其产品约定与现有规矩冗余，不做内容迁移。
- **BREAKING** 删除 [`AGENTS.md`](../../../AGENTS.md)：协作规则不再以仓库文件形式固化（内容已知且冗余，还会长期占用 agent 系统上下文）。
- **BREAKING** 端侧自检工具迁移：[`verification/checks/`](../../../verification/checks) 全部内容并入 [`scripts/tests/`](../../../scripts/tests)，成为仓库唯一 uv 项目源（`pyproject.toml`/`uv.lock` 只此一份，日后临时验证脚本也落在这里）。自检命令由 `cd verification/checks && uv run python -m checks` 变为 `cd scripts/tests && uv run python -m checks`，单命令、只读、无 Kotlin/Android 环境要求的语义不变。
- **BREAKING** 删除 [`verification/`](../../../verification) 其余内容：[`DEVICE_CHECKLIST.md`](../../../verification/DEVICE_CHECKLIST.md) 中央清单直接删除——真机人工验收以各 change `tasks.md` 的人工勾选为载体，不另设清单；一次性验证报告（[`BUILD_REPORT.md`](../../../verification/BUILD_REPORT.md)、[`CLOUD_PROVIDERS.md`](../../../verification/CLOUD_PROVIDERS.md)、[`FEATURES_0_1_5.md`](../../../verification/FEATURES_0_1_5.md)、[`ICON_UPDATE.md`](../../../verification/ICON_UPDATE.md)、[`ISSUES_1_8.md`](../../../verification/ISSUES_1_8.md)）、`screenshots/` 与 `checks/probe_result.txt` 随目录整体删除（与 README、git 历史冗余，必要内容为零）。
- 更新失效引用：[`openspec/config.yaml`](../../../openspec/config.yaml) 的 context 两处（自检命令路径、真机验收依据）、[`README.md`](../../../README.md)（工程结构表与"完整产品约定"链接）、[`docs/openai-tool-calling/README.md`](../../../docs/openai-tool-calling/README.md) 末段、[`.gitignore`](../../../.gitignore) 的 `verification/` 条目。
- 三个进行中 change（`shrink-icon-and-retitle-app`、`add-log-level-and-export`、`use-static-debug-keystore`）的**未完成**任务里对 `verification/` 的路径引用同步改到新位置；已归档 change 作为历史不回改。
- README 归位：只承载用户文档（使用、构建、供应商），不再充当约定权威入口。

## Capabilities

### New Capabilities

- `spec-governance`: 仓库治理的持久约束——openspec 是唯一 spec-driven 来源，产品/工程约定只由 `openspec/` 承载；MUST NOT 回归平行规范文档（根目录 `IMPLEMENTATION_PLAN.md`/`AGENTS.md` 式文档）、中央人工验收清单或独立验证报告目录；真机人工验收随 change 的 `tasks.md` 勾选执行；README 只承载用户文档。
- `static-checks`: 端侧提交前自检的持久行为——单命令只读执行、失败以非零退出码列出失败项、不要求 Kotlin/JDK/Android SDK；工具以 `scripts/tests/` 为仓库唯一 uv 源，新增验证脚本 SHALL 落在该处而非另立顶层目录。

### Modified Capabilities

（无。`model-download` 需求不变；历史 change 的 delta 不回改。）

## Impact

- **删除**：`IMPLEMENTATION_PLAN.md`、`AGENTS.md`、`verification/` 全目录。
- **迁移**：`verification/checks/`（5 项检查、`probe_download_sources.py`、uv 项目文件）→ `scripts/tests/`。
- **修改**：`openspec/config.yaml`、`README.md`、`docs/openai-tool-calling/README.md`、`.gitignore`、3 个进行中 change 的未完成任务引用。
- **CI**：[`.github/workflows/android.yml`](../../../.github/workflows/android.yml) 无需改动（`python -m unittest discover -s scripts/tests` 与新增的 `checks/` 包不冲突，检查脚本不匹配 `test*.py` 模式）。
- **开发者工作流**：端侧自检命令路径变化（BREAKING）；此前依赖 `AGENTS.md` 自动加载规则的工具链需改用其自身全局配置（规则内容不再随仓库分发）。
- **不影响**：应用代码（`app/`、`core/`）零改动；已归档 change 的历史工件保持原样。
