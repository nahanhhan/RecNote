# Tasks

## 1. 检查工具迁移（static-checks）

- [x] 1.1 将 `verification/checks/` 的 `pyproject.toml`、`uv.lock`、`checks/` 包（`__init__.py`、`__main__.py`、`json_assets.py`、`kotlin_comments.py`、`log_redaction.py`、`model_sources.py`、`version_consistency.py`）与 `probe_download_sources.py` 迁入 [`scripts/tests/`](../../../scripts/tests)，同步更新 [`checks/__main__.py`](../../../verification/checks/checks/__main__.py) 文件头路径注释（`REPO_ROOT = parents[3]` 计算逻辑不变，5 项检查与输出格式不变）——验证：`cd scripts/tests && uv run python -m checks` 输出 5 项检查全过、退出码 0
- [x] 1.2 确认 [`scripts/tests/`](../../../scripts/tests) 为仓库唯一 uv 源且与 unittest 共存互不干扰——验证：全仓 `pyproject.toml`/`uv.lock` 仅存于 `scripts/tests/`；`uv run --project scripts/tests python -m unittest discover -s scripts/tests` 通过（端侧执行一律经 uv；CI 同命令无需改动）
- [ ] 1.3 本批验收：`cd scripts/tests && uv run python -m checks` 全绿（只读，不修改被检查文件），推送后 CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过（工程验证口径）

## 2. 残余删除（spec-governance）

- [x] 2.1 删除 [`IMPLEMENTATION_PLAN.md`](../../../IMPLEMENTATION_PLAN.md) 与 [`AGENTS.md`](../../../AGENTS.md)（内容已确认与现有规矩冗余、不做迁移）——验证：两文件从工作区与 git 索引消失
- [x] 2.2 删除 [`verification/`](../../../verification) 其余全部内容：5 份一次性验证报告、[`DEVICE_CHECKLIST.md`](../../../verification/DEVICE_CHECKLIST.md)、`screenshots/`、`checks/probe_result.txt`（必要内容为零：真机人工验收由各 change 的 tasks.md 勾选承担）——验证：仓库不存在 `verification/` 目录
- [x] 2.3 本批验收：删除后 `cd scripts/tests && uv run python -m checks` 仍全绿（检查目标文件不受删除影响）

## 3. 引用修复

- [ ] 3.1 [`openspec/config.yaml`](../../../openspec/config.yaml) context：两行失效引用改写（自检命令路径 `verification/checks` → `scripts/tests`；「真机验收按 verification/DEVICE_CHECKLIST.md 人工执行」→「真机验收由各 change 的 tasks.md 人工勾选执行」），并增补两行规矩硬化（端侧 Python 验证一律经 `uv run`、验证脚本统一收于 `scripts/tests` 唯一 uv 源；夹具同步：开发规范同批同步本 context、新增验证测试同批注册进 `scripts/tests` 检查清单）——验证：diff 仅四行（两行改写 + 两行增补），其余规矩行不动
- [ ] 3.2 [`README.md`](../../../README.md)：工程结构表 `verification/` 行替换为 `scripts/tests/`（端侧自检与脚本测试），删除「完整产品约定见 IMPLEMENTATION_PLAN.md」一行——验证：`cd scripts/tests && uv run python -m checks` 中版本一致性检查通过（版本行未动）
- [ ] 3.3 [`docs/openai-tool-calling/README.md`](../../../docs/openai-tool-calling/README.md) 末段对 `verification/` 的引用改为「构建验证以 CI 结果为准，验证记录随 change 工件归档」口径；[`.gitignore`](../../../.gitignore) 删除 `verification/local-*.txt` 条目，`.venv` 与 `__pycache__` 条目改指 `scripts/tests/` ——验证：全仓搜索 `verification/`、`IMPLEMENTATION_PLAN`、`AGENTS.md`，在非历史工件（`openspec/changes/archive/` 与已勾选任务除外）零命中
- [ ] 3.4 本批验收：`cd scripts/tests && uv run python -m checks` 全绿

## 4. 进行中 change 的引用同步

- [ ] 4.1 [`use-static-debug-keystore`](../use-static-debug-keystore/tasks.md) 4.1：`verification/DEVICE_CHECKLIST.md` 的表述改为「按本 change tasks 对应人工验收项勾选执行」——验证：`openspec validate use-static-debug-keystore` 通过
- [ ] 4.2 [`shrink-icon-and-retitle-app`](../shrink-icon-and-retitle-app/tasks.md) 1.5/2.4/3.4 的自检命令路径改 `scripts/tests`，4.1 的 DEVICE_CHECKLIST 引用按 4.1 同口径改写——验证：`openspec validate shrink-icon-and-retitle-app` 通过
- [ ] 4.3 [`add-log-level-and-export`](../add-log-level-and-export/tasks.md) 1.4/3.5 的自检命令路径改 `scripts/tests`，6.2 的真机验收表述按 4.1 同口径改写——验证：`openspec validate add-log-level-and-export` 通过

## 5. 全量集成验收

- [ ] 5.1 端侧自检与脚本测试全绿：`cd scripts/tests && uv run python -m checks` 与 `uv run --project scripts/tests python -m unittest discover -s scripts/tests` 均退出码 0——验证：命令输出记录于本 change
- [ ] 5.2 `openspec validate consolidate-spec-driven-governance` 通过，`openspec status --change consolidate-spec-driven-governance` 显示全部工件完成——验证：命令输出无错误
- [ ] 5.3 推送后 CI（`:core:test`、`:app:lintDebug`、`:app:assembleDebug`）通过，仅表述为工程验证通过（本变更无应用行为变化，无真机人工验收项）——验证：CI 工作流全绿
