# UNIT-CROSS-DB-EXAMPLES 第 4 轮修复请求（最后一轮）

- 上一轮：round-3
- Reviewer 结论：fail（Confidence: High, Risk Level: High）
- 主 Agent 结论：证据充分、已独立复核复现，接受 fail 并转为修复请求
- 阻塞问题：
  - mysql-pg 五个用例（01-05）的目标装载库与查询库不一致：脚本用 `psql -h 127.0.0.1 -p 5432 -U "$PG_USER" -d postgres` 装载（`run-comparison-test.sh:257-259`），`load-postgresql.sql` 全文无 `CREATE DATABASE`/`\connect`/`consilens_demo`，只在连接库 public schema 建表；五个 pg YAML 的 URL 却全部指向 `jdbc:postgresql://127.0.0.1:5432/consilens_demo?currentSchema=public`。标准 PostgreSQL 下建在 postgres 库的表在 consilens_demo 库不可见，目标端查表必然失败。实现者 round-3 的库级扫描把 pg 整组"按 public schema 豁免"，恰好跳过该检查，属验证盲区。
- 必需修改（三选一，推荐 a 或 b）：
  a. `load-postgresql.sql` 头部增加 `CREATE DATABASE consilens_demo;` + `\connect consilens_demo`（psql 元命令，脚本已用 psql 执行）；
  b. 脚本 pg 装载改为 `-d consilens_demo`（前提该库已存在，需脚本头注明）；
  c. 将五个 pg YAML 的 URL 改为 `jdbc:postgresql://127.0.0.1:5432/postgres?currentSchema=public` 与装载库一致。
  无论选哪种，都必须去掉库级契约扫描中 pg 的整组豁免，改为逐 YAML 校验（目标 URL 库名与装载 SQL 实际装载库一致），并把修正后的扫描输出记录到 validation.md。
- 需要检查的文件：`examples/mysql-pg/load-postgresql.sql`、`examples/mysql-pg/0{1..5}.yaml`、`examples/run-comparison-test.sh`（pg 装载段）。
- 需要新增或重新运行的测试：修正后的库级契约扫描（不再豁免 pg）、`bash -n`、`mvn -pl consilens-cli -am test-compile`、集成测试默认模式。
- 需要重复的集成检查：50 个 YAML 的 URL 库名与 19 个 load SQL 实际装载库逐一对应；pg 装载命令与 SQL 建库/切库方式一致。
- 约束：不 stage、不 commit、不 push；不得改动 UNIT-SQL-GENERATION 拥有的文件。
- 不得破坏：round-2/round-3 已验证成立的 shade fat jar、脚本 JAR 路径、凭据外置、.gitignore、集成测试启用机制、02/03/05 表级契约、`consilens.it.<NAME>` 大写命名、Doris 9030 端口。
- 下一轮必须提供的证据：修正后的 pg 装载 SQL 或脚本片段、逐 YAML 库级契约扫描输出（FAILURES=0 且无豁免）、`bash -n` 与 test-compile 结果。
