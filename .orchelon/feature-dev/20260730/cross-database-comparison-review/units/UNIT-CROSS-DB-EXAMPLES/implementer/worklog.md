# UNIT-CROSS-DB-EXAMPLES 工作日志

- round-2 开始：主 Agent 撤回 Compose 挂载误报；修复范围以最新 scope 和 repair-request 为准。

## round-2 实现记录

- `consilens-cli/pom.xml`：参照 `consilens-mcp/pom.xml` 增加 maven-shade-plugin 3.5.3，产出可 `java -jar` 运行的 fat jar；同时排除依赖签名文件（`META-INF/*.SF/DSA/RSA`），否则启动报 `SecurityException: Invalid signature file digest`。
- 集成测试：`@Disabled` 改为 `@EnabledIfSystemProperty(named = "consilens.it.enabled", matches = "true")`；删除误导的 `-Dtest.profile=integration` 注释；`EXAMPLES_DIR` 改为基于 `target/test-classes` 定位（向上三级 + `examples`），不再依赖 CWD；`testEnvironment()` 改为从 `consilens.it.<name>` 系统属性或同名环境变量读取，仓库内无明文默认凭据。
- SQL 契约：修复 `mysql-oceanbase/load-oceanbase.sql:103` 与 `mysql-presto/load-presto-target.sql:105` 的 `5000.0000', 'extra'` 引号错位；Presto 01/03 target 库统一为 `consilens_demo_target`，04/05 统一为 `mydb_target`（与 load SQL 装载库一致）。
- 凭据外置：`examples/configs/{cross-db,same-db}/*oceanbase*.yaml` 明文 `root/Admin123_123` 改 `${env.*}` 占位符；`mysql-oracle/load-oracle.sql` 的 `CREATE USER ... IDENTIFIED BY Kuanilens_Oracle_2026!` 改为 `__ORACLE_APP_PASSWORD__` 占位符并说明注入方式。
- 运行脚本重写：JAR 路径改为 Maven 产物 `consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar`（支持 `CONSILENS_CLI_JAR` 覆盖），构建提示改为 `./mvnw -pl consilens-cli -am package`；所有端口参数化（`MYSQL_PORT`、`DORIS_PORT` 等）；凭据无默认值，缺失即 fail-fast；按 pair 为 10 个目标实现 best-effort 装载（客户端存在时执行，缺失时提示人工装载）；Oracle 明确为人工步骤。修复 `require_env` 在 `log_error` 定义前调用导致的函数未定义 bug（MISSING-ENV-EXIT=127）。
- `.gitignore`：新增 `examples/mysql-doris/*.bak`。
- 未修改 `deploy/docker-compose.yml`（round-1 已更正为误报）；未触碰 UNIT-SQL-GENERATION 拥有的任何文件。

## 验证中发现但未修复的问题（归属其他单元或既有）

- `./mvnw -pl consilens-cli -am test`：上游 `consilens-connector-oracle` 的 `OracleSqlQueryGeneratorTest.testGetChecksumSQL` 失败（expected true but was false），该文件属于 UNIT-SQL-GENERATION 单元的既有修改，本单元不处理。
- `./mvnw -pl consilens-cli test`：3 个 Ai 命令测试失败（`AiConfigCommandTest`、`AiDiagnoseCommandTest`、`AiExplainCommandTest`，均 expected 0 but was 1）与 `ExampleConfigurationCompatibilityTest` 的 2 个 error（顶层 `examples/mysql-to-oracle-checksum.yaml`、`same-db-oracle-comparison.yaml` 缺少 `ORACLE_USER` 测试环境变量）均为未修改文件或既有环境问题；`ExampleConfigurationCompatibilityTest` 的 `testEnvironment()` 只含 8 个变量，未覆盖 Oracle 等占位符变量。
- 本单元新增的 `CrossDatabaseComparisonIntegrationTest` 单独运行 3 项：2 通过、1 按条件跳过（参数化用例）。

## round-3 实现记录

- 02 聚合表契约：presto/trino 目标装载在 `production_target.fact_orders` 装载段后新增 `consilens_demo_target.daily_order_summary` 建表+INSERT（参照 pg/tidb 模式，GROUP BY DATE(created_at), status），并在 validation 段加计数查询；sqlserver 在 `fact_orders` 装载后新增 `USE consilens_demo` 下 `daily_order_summary` 建表+INSERT（T-SQL 风格，`CAST(created_at AS DATE)`）。
- 03 大表契约：presto/trino 03 从 `type: sql` 查询 `consilens_performance_demo_table` 改为与 pg/tidb 一致的 `fact_orders` 大表模式（源端 URL `mysql/production`、目标端 `mysql/production_target`，table `fact_orders`，keys/fields/并发参数对齐 pg 03）。两侧 `load-mysql.sql` 与目标装载均已建 `production.fact_orders`/`production_target.fact_orders`，契约成立。注意：round-2 review 描述 03 为“URL 指向 consilens_demo_target 且查 fact_orders”与实际代码不符（当时 03 查的是 consilens_performance_demo_table 且可运行）；本次按 repair-request 意图统一为 fact_orders 大表契约。
- oceanbase 05：资源名改为 `mydb.orders`/`mydb.orders_backup`（与 starrocks 05 模式一致；`load-oceanbase.sql` 在 mydb 库建表），URL 保持 `consilens_demo`。
- 集成测试属性命名：`consilens.it.<name>` 统一为与脚本环境变量名一致的大写（如 `-Dconsilens.it.MYSQL_USER=root`），Javadoc 同步更新，删除 `toLowerCase(Locale.ROOT)` 映射。
- Doris 端口贯通：`examples/mysql-doris/0{1..5}.yaml` 全部 9031 → 9030，与脚本 `DORIS_PORT` 默认 9030 及 Doris 默认 FE 查询端口一致；`.bak` 旧稿亦为 9030。
- 脚本头披露：新增 Trino/Presto `mysql` catalog 必须指向 127.0.0.1:13306 MySQL 实例的前提、Doris 端口假设（需人工确认）、sqlcmd 临时文件说明三条。
- sqlcmd：`-i /dev/stdin` 改为 `-i {SQL_FILE}` 占位符，`load_target_data` 支持占位符替换（其余 loader 仍走 stdin 重定向），避免跨平台 stdin 兼容风险。

## round-3 验证发现

- 契约扫描脚本首版暴露 25 个 TABLE-MISSING 均为扫描脚本自身缩进/字段提取缺陷，修正后 50 YAML × 19 load SQL 表级、库级契约扫描 FAILURES=0。
- 03 修复前与 round-2 review 描述存在差异（见上），已在 change-summary 与报告中披露。

## round-4 实现记录

- P0（mysql-pg 装载库契约）：选择 repair-request 方案 a。`examples/mysql-pg/load-postgresql.sql` 头部新增条件建库（`SELECT 'CREATE DATABASE consilens_demo' WHERE NOT EXISTS (...) \gexec`）+ `\connect consilens_demo`，脚本 psql 装载仍以 `-d postgres` 起始连接，随后切换到 `consilens_demo` 建 public 表；五个 pg YAML URL（`/consilens_demo?currentSchema=public`）与装载库一致。`\gexec` 保证幂等（库已存在时不报错）。
- 库级契约扫描去豁免：重写逐 YAML 扫描（mysql-presto/trino/clickhouse 按 URL 库 + 表限定符、sqlserver 按 databaseName ↔ USE/CREATE DATABASE、oracle 按 SID 出现在装载说明、pg 校验 `consilens_demo` 被创建/连接且表在 public schema），不再整组豁免 pg；扫描输出 FAILURES=0。
- 未触碰 round-2/round-3 已验证成果：shade fat jar、脚本 JAR 路径、凭据外置、.gitignore、集成测试启用机制、02/03/05 表级契约、`consilens.it.<NAME>`、Doris 9030 均未回退（spot check 通过）。

## round-4 验证发现

- 扫描脚本首版对 Oracle URL（`jdbc:oracle:thin:@host:port:SID`）解析失败报 URL-DB-UNPARSEABLE，修正后 FAILURES=0（Oracle 分支改为 SID 存在性检查，load-oracle.sql 头部说明注释含 ORCL）。
