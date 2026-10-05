# Design

## Context

现状见 proposal.md - Why。与本设计相关的事实约束：

- [`verification/checks/checks/__main__.py`](../../../verification/checks/checks/__main__.py) 用 `REPO_ROOT = Path(__file__).resolve().parents[3]` 定位仓库根，5 个检查全部以仓库相对路径引用目标文件（`app/src`、`core/src`、`docs/`、`README.md`、`app/build.gradle.kts`），检查内部**不引用** `verification/` 或 `IMPLEMENTATION_PLAN.md`。
- [`scripts/tests/`](../../../scripts/tests) 现有 [`test_publish_release.py`](../../../scripts/tests/test_publish_release.py)，由 [`.github/workflows/android.yml`](../../../.github/workflows/android.yml) 的 `python -m unittest discover -s scripts/tests` 执行；该命令不要求 uv。
- [`openspec/config.yaml`](../../../openspec/config.yaml) 的 context 有两行引用将失效：自检命令路径（`verification/checks`）与真机验收依据（`verification/DEVICE_CHECKLIST.md`）。
- 三个进行中 change 的未完成任务散布 `verification/` 路径引用；`openspec/changes/archive/` 下历史工件同样引用，但按治理口径不回改。

## Goals / Non-Goals

**Goals:**

- 迁移后 `uv run python -m checks` 的检查集、输出格式、退出码语义完全不变（满足 [`static-checks`](specs/static-checks/spec.md) 与 [`spec-governance`](specs/spec-governance/spec.md)）。
- 删除与引用修复在同一批次完成，仓库任意时刻不出现"引用指向已删除路径"的中间态（rebase 窗口除外）。
- 仓库只保留一个 uv 项目文件位置（`scripts/tests/`）。

**Non-Goals:**

- 不改 `app/`、`core/` 应用代码与 Gradle 构建行为。
- 不改 CI 工作流（含产物名 `verification-reports`）。
- 不回改 `openspec/changes/archive/` 与已勾选任务中的历史引用。
- 不迁移 [`IMPLEMENTATION_PLAN.md`](../../../IMPLEMENTATION_PLAN.md)、[`AGENTS.md`](../../../AGENTS.md) 与 [`verification/`](../../../verification) 报告的任何内容（已确认冗余）。
- 不统一测试框架：`test_publish_release.py` 保持 unittest（本地一律经 uv 执行，CI 侧维持系统 Python 3.12 不变）。

## Decisions

1. **`checks/` 以原包名整体搬进 `scripts/tests/`，入口不变。** 目标布局：`scripts/tests/{pyproject.toml, uv.lock, checks/, probe_download_sources.py, test_publish_release.py}`。`scripts/tests/checks/__main__.py` 与旧路径深度相同，`parents[3]` 仍指仓库根，只改文件头注释中的路径说明。[`checks/__main__.py`](../../../verification/checks/checks/__main__.py) 的 `CHECKS` 注册表即「uv 检查清单」，新增验证测试同批注册进该表（见 [`static-checks`](specs/static-checks/spec.md)），不另立清单。备选"并入 unittest 作为 `test_checks.py`"被否决：混淆"端侧只读自检"与"CI 单测"两种语义，且输出/退出码契约会变。
2. **uv 项目元数据保持不变。** `pyproject.toml` 的 `name = "lecture-checks"`、`package = false` 原样迁移，仅 description 补充"验证脚本"字样；`uv.lock` 无需重新生成，避免无谓漂移。备选"改名 `lecture-tests`"被否决：纯装饰性收益，反而引入 lock 漂移风险。
3. **`test_publish_release.py` 与 uv 项目共处一目录，端侧执行一律经 uv。** unittest discover 只匹配 `test*.py`，不触碰 `checks/` 包；本地跑 unittest 用 `uv run --project scripts/tests python -m unittest discover -s scripts/tests`，任何 Python 验证 MUST 经 `uv run` 执行、MUST NOT 裸跑系统 Python。uv 唯一源 = uv 项目文件唯一 + 端侧执行一律经 uv；CI 的同名 discover 命令保持系统 Python 3.12 不变（Non-Goal）。
4. **删除取"一次清空"而非"先归档到 docs/"。** `verification/` 报告、`screenshots/`、`probe_result.txt` 与 README、git 历史冗余；挪进 `docs/` 只是给残余换目录名，违背 [`spec-governance`](specs/spec-governance/spec.md)。`DEVICE_CHECKLIST.md` 的职责由各 change `tasks.md` 人工勾选承担，不再设中央清单。
5. **进行中 change 只改未勾选任务里的路径字符串。** 涉及 `use-static-debug-keystore` 4.1、`shrink-icon-and-retitle-app` 1.5/2.4/3.4/4.1、`add-log-level-and-export` 1.4/3.5/6.2：`verification/checks` 命令改为 `scripts/tests`，`verification/DEVICE_CHECKLIST.md` 的表述改为"按本 change tasks 对应人工验收项勾选"。已勾选任务、proposal/design 叙述与 archive/ 保持原样。备选"全部工件统一改"被否决：重写历史叙述制造虚假记录。
6. **`config.yaml` context 修两行失效引用，并增补一行夹具同步规矩。** 两行失效引用改法不变（自检命令路径 → `scripts/tests`；真机验收依据 → 各 change 的 `tasks.md` 人工勾选）；另增一行：开发规范变更同批同步本 context、新增验证测试同批注册进 `scripts/tests` 检查清单——按"有规范进 config.yaml、有测试进检查清单"的口径，对应 [`spec-governance`](specs/spec-governance/spec.md) 与 [`static-checks`](specs/static-checks/spec.md)。其余规矩行不动，避免与 spec 形成新的重复。
7. **README 只做外科手术。** 工程结构表 `verification/` 行替换为 `scripts/tests/`（端侧自检与脚本测试），删除"完整产品约定见 IMPLEMENTATION_PLAN.md"一行；不触碰受 [`version_consistency.py`](../../../verification/checks/checks/version_consistency.py) 检查的版本行，不动其他用户文档。

## Risks / Trade-offs

- [三个进行中 change 的 `tasks.md` 同批修改，若他人正在推进这些 change 会产生合并冲突] → 路径替换放最后一批、只改字符串不改任务结构；出现冲突时以"新路径"为准手工合并。
- [本地习惯或外部链接仍指向 `verification/`/`IMPLEMENTATION_PLAN.md`（如 GitHub issue 历史评论中的链接）] → 旧路径删除后引用立即显形；issue 历史链接属讨论存档，接受 404，不迁移。
- [删除 `DEVICE_CHECKLIST.md` 后未执行的验收项失去集中视图] → 已确认人工勾选 `tasks.md` 即验收流程；未执行项分散保留在各进行中 change 的任务里，v2 新 change 按 spec 场景写验收项，不回填中央清单。
- [依赖 `AGENTS.md` 自动加载规则的协作者工具链失效] → 规则内容由各工具自身全局配置承载（用户已确认内容已知）；仓库不再分发，属预期 BREAKING。

## Migration Plan

单提交（或单 PR）原子完成，顺序：① `checks/` 迁入 `scripts/tests/` 并跑通 `uv run python -m checks`；② 删除 `IMPLEMENTATION_PLAN.md`、`AGENTS.md`、`verification/`；③ 修 `config.yaml`、`README.md`、`docs/openai-tool-calling/README.md`、`.gitignore`；④ 更新三个进行中 change 的未勾选任务引用；⑤ 全量自检（`uv run python -m checks` + `openspec validate`）后推送，以 CI 结果为工程验证结论。回滚：`git revert` 该提交即可完整恢复旧布局。
