# Spec Delta

## Purpose

固化仓库治理约束：openspec 是唯一 spec-driven 规范来源，产品/工程约定、人工验收与一次性验证记录均随 change 工件承载，README 只做用户文档。

## ADDED Requirements

### Requirement: openspec 为唯一规范来源

产品与工程约定 SHALL 只由 `openspec/`（specs、changes、`config.yaml` context）承载；仓库 MUST NOT 出现第二套规范载体，例如根目录实施计划/代理规则式规范文档、独立的中央人工验收清单、或 `verification/` 式版本验证报告目录。

#### Scenario: 新约定只进 openspec

- **WHEN** 一条新的产品约定或开发规矩需要固化
- **THEN** 写入 openspec 的 spec、change 工件或 `config.yaml` context，仓库根 MUST NOT 新增实施计划/规则式 md 文档

#### Scenario: 人工验收与验证记录随 change 走

- **WHEN** 某个 change 需要真机人工验收或产生一次性验证记录
- **THEN** 验收步骤写入该 change 的 `tasks.md` 由人工勾选、验证结论记录在 change 工件内，MUST NOT 新建中央验收清单或独立报告目录

### Requirement: 开发规范同批同步 config.yaml

新增、修改或废止开发规范的变更 SHALL 在同一变更内同步 `openspec/config.yaml` 的 context（开发规范的唯一归口）；规范 MUST NOT 只存在于 change 工件或会话记忆中，context 中 MUST NOT 残留已废止条目。

#### Scenario: 新规范同批入档

- **WHEN** 某个 change 引入一条新的开发规范
- **THEN** `config.yaml` context 在该变更内出现对应条目并随其一起提交，不依赖事后补写

#### Scenario: 规范废止同批清理

- **WHEN** 某条开发规范被废止或改写
- **THEN** `config.yaml` context 在同一变更内更新，不残留失效条目

### Requirement: README 只承载用户文档

README SHALL 只承载用户可见文档（功能、构建、使用、供应商说明）；产品与工程约定 MUST NOT 以 README 或其链接的独立文档为权威，README 中 MUST NOT 存在"完整产品约定"式权威入口链接。

#### Scenario: 约定类内容的去向

- **WHEN** 待写内容属于产品约定或开发规范而非用户可见行为
- **THEN** 内容进入 openspec 而非 README

#### Scenario: 不再链接独立约定文档

- **WHEN** 查阅 README
- **THEN** 其中不存在指向 openspec 之外约定/计划类文档的权威入口链接
