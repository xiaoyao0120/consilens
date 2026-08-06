# UNIT-CROSS-DB-EXAMPLES 第 3 轮修复请求

- 上一轮：round-2
- Reviewer 结论：fail（Confidence: High, Risk Level: High）
- 主 Agent 结论：证据充分、已逐条复核复现，接受 fail 并转为修复请求
- 阻塞问题：
  - Presto/Trino/SQL Server 的 02 用例引用不存在的 `daily_order_summary` 表（对应 target load SQL 无建表），示例必然失败。
  - Presto/Trino 的 03 用例 URL 指向 `consilens_demo_target`，但 `fact_orders` 装载在 `production_target` 库，查库必然失败。
  - OceanBase 05 用例 URL 指向 `consilens_demo` 且资源名为 `orders`/`orders_backup`，但 `load-oceanbase.sql` 在 `mydb` 库建表，示例必然失败。
- 必需修改：
  1. 为 presto/trino/sqlserver 的 02 用例补齐 `daily_order_summary` 目标表建表+装载（参考 `examples/mysql-pg/load-postgresql.sql:256-268` 模式），或把 02 YAML target 改为 `type: sql` 聚合查询（参考 starrocks/oceanbase/doris 02 模式）；源端 `load-mysql.sql` 若无该表则一并处理。
  2. 修正 presto/trino 03 YAML URL 库名为 `production_target`（`jdbc:presto://127.0.0.1:8085/mysql/production_target`、`jdbc:trino://127.0.0.1:8081/mysql/production_target`），与 `fact_orders` 装载库一致。
  3. 修正 oceanbase 05 YAML：URL 改 `mydb` 库，或资源名显式写 `mydb.orders`/`mydb.orders_backup`（推荐后者，与 starrocks 05 模式一致）。
  4. 统一 `consilens.it.<name>` 系统属性命名大小写（建议与脚本环境变量名一致，如 `consilens.it.MYSQL_USER`），并修正测试类 Javadoc。
  5. 统一 Doris 端口默认值：脚本 `DORIS_PORT` 默认 9030 与 `examples/mysql-doris/0{1..5}.yaml` 硬编码 9031 不一致；统一后二选一，并在脚本头披露（外部 compose 不在仓库，需人工确认）。
  6. 脚本头明确披露 Trino/Presto 的 `mysql` catalog 必须指向 13306 MySQL 实例这一前提。
  7. `sqlcmd -i /dev/stdin` 兼容性风险：改用进程替换或临时文件，无法验证时在脚本头披露。
- 需要检查的文件：`examples/mysql-presto/0{1..5}.yaml`、`examples/mysql-trino/0{1..5}.yaml`、`examples/mysql-sqlserver/0{1..5}.yaml`、`examples/mysql-oceanbase/0{1..5}.yaml`、`examples/mysql-doris/0{1..5}.yaml`、对应 10 组 `load-*.sql`、`examples/run-comparison-test.sh`、`CrossDatabaseComparisonIntegrationTest.java`。
- 需要新增或重新运行的测试：50 个 YAML × 19 个 load SQL 的表级契约扫描（target 表名在对应装载 SQL 中存在、库名与 URL 一致）纳入验证证据；`bash -n`；`mvn -pl consilens-cli -am test-compile`；集成测试默认模式。
- 需要重复的集成检查：YAML 资源表名 ↔ load SQL 建表/装载表名 ↔ URL 库名三者一致；Doris 端口脚本与 YAML 一致；`consilens.it.*` 命名与文档一致。
- 约束：不 stage、不 commit、不 push；不得改动 UNIT-SQL-GENERATION 拥有的文件（SQL 生成器、DataTypeHandler、TableSegment.java、两个 OceanBase 测试、deploy/docker-compose.yml）。
- 不得破坏：round-2 已验证成立的 shade fat jar、脚本 JAR 路径、凭据外置、`.gitignore`、集成测试启用机制。
- 下一轮必须提供的证据：表级契约扫描结果（可脚本化 `rg`/`grep` 交叉比对并记录输出）、修正后的 YAML/load SQL 片段、Doris 端口一致性说明、脚本头披露新增内容。
