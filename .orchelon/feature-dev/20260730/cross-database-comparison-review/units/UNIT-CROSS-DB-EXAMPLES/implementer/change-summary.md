# UNIT-CROSS-DB-EXAMPLES 修改摘要

- 轮次：round-4（修复，最后一个修复轮次）
- 已实现目标：round-4 repair-request（P0 mysql-pg 装载库契约）完成。
- 主要修改文件：
  - `examples/mysql-pg/load-postgresql.sql`：头部新增条件建库 `consilens_demo`（`\gexec` 幂等）+ `\connect consilens_demo`，装载库与五个 pg YAML 查询库一致
- 辅助修改文件：本单元事实源 4 件。
- 行为变化：mysql-pg 五个用例（01-05）在标准 PostgreSQL 下目标表可见（此前建在 postgres 库、查询 consilens_demo 库必然失败）。
- 数据库或 Migration 变化：仅示例装载 SQL 新增建库/切库元命令（幂等）。
- API 或契约变化：无。
- 前端行为变化：不适用。
- 新增或更新测试：无新增测试类；库级契约扫描改为无豁免逐 YAML 校验（结果记录于 validation.md）。
- 计划一致性：follows
- 偏离原因：无。
- 兼容性考虑：round-2/round-3 已验证成果（shade fat jar、脚本 JAR 路径、凭据外置、.gitignore、集成测试启用机制、02/03/05 契约、`consilens.it.<NAME>`、Doris 9030）均未回退；脚本 psql 装载命令不变（仍 `-d postgres` 起始，SQL 内部切库）。
- Reviewer 应重点关注：pg 装载 SQL 建库/切库片段、无豁免库级扫描逻辑与输出。
- 剩余风险：pg `\gexec`/`\connect` 元命令未在真实 psql 环境验证；外部数据库未联调；`-am test` 上游 Oracle 测试失败属另一单元。
