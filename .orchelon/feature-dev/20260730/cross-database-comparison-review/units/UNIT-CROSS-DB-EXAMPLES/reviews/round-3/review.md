# UNIT-CROSS-DB-EXAMPLES 第 3 轮 Review

## Verdict

**fail**

## Confidence

High

## Risk Level

High

## 风险等级理由

round-3 repair-request 的七项必需修改中，六项已实质修复并独立复验通过；但独立复跑表级/库级契约扫描时发现一个被实现者"pg 按 public schema 豁免"掩盖的阻断缺陷：`examples/mysql-pg/load-postgresql.sql` 全文没有任何 `CREATE DATABASE`/`\connect`/`consilens_demo`，而脚本用 `psql -d postgres` 装载（默认连接 postgres 库、建 public 表），五个 pg YAML 的 URL 却全部指向 `consilens_demo` 库（`?currentSchema=public`）。在标准 PostgreSQL 安装中，建在 postgres 库 public schema 的表在 consilens_demo 库不可见，mysql-pg 五个用例（01-05）目标端查表必然失败。该缺陷与本单元验收标准"YAML 库名与装载 SQL 装载库一致"直接冲突，且被验证报告的豁免逻辑系统性跳过，故判 fail。

## Confidence 说明

pg 装载库不一致的判定基于仓库内证据（load SQL 无建库/切库语句 + psql `-d postgres` + YAML 指向 consilens_demo）。外部 PostgreSQL 环境（docker-compose 中无 pg 服务）不在仓库内，无法排除"PGUSER/默认连接库被外部配置为 consilens_demo"的极小概率，故对"必然失败"的置信度为 High 而非 absolute；但作为未披露、未验证的契约缺口，已满足 fail 标准。

## 已读文件

### 事实源

- `/Users/szh/.agents/skills/medium-dev-loop/SKILL.md`（round-2 已完整阅读，本轮按需回看裁决规则）
- 仓库根 `AGENTS.md`（文件不存在；以对话提供的十二条铁律为准）
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/task-brief.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/implementation-plan.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/work-breakdown.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/run-state.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/scope.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/reviews/round-2/review.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/iterations/round-3/repair-request.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/change-summary.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/validation.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/resume-state.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/worklog.md`

### 审查范围内文件

- `examples/run-comparison-test.sh`（完整）
- `examples/mysql-presto/{02-detail-to-aggregate.yaml,03-large-table.yaml,load-presto-target.sql,load-mysql.sql}`（完整）
- `examples/mysql-trino/{02-detail-to-aggregate.yaml,03-large-table.yaml,load-trino-target.sql,load-mysql.sql}`（完整）
- `examples/mysql-sqlserver/{02-detail-to-aggregate.yaml,load-sqlserver.sql}`（完整）
- `examples/mysql-oceanbase/05-same-db-join.yaml`（完整）
- `examples/mysql-doris/0{1..5}.yaml`（端口扫描 + 05 完整）
- `consilens-cli/src/test/java/com/consilens/cli/config/CrossDatabaseComparisonIntegrationTest.java`（Javadoc + testEnvironment 完整）
- `examples/mysql-pg/{0{1..5}.yaml,load-postgresql.sql}`（全量；发现阻断缺陷）
- `consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar`（fat jar smoke）

### 抽样说明

50 个 YAML × 19 个 load SQL 全部经表级/库级契约扫描覆盖（我独立复跑）；presto/trino/sqlserver/oceanbase/doris/pg 六组高风险用例完整阅读；其余组（clickhouse/tidb/starrocks/oracle）契约扫描通过，未逐行读重复 INSERT 段。

## 逐项发现（按严重度排序）

### P0 / 阻断

#### 1. mysql-pg 五个用例的目标装载库与查询库不一致（新发现，被验证豁免掩盖）

- 文件：`examples/run-comparison-test.sh:257-259`、`examples/mysql-pg/load-postgresql.sql`（全文）、`examples/mysql-pg/0{1..5}.yaml`（URL 均含 `consilens_demo`）
- 问题：脚本用 `psql -h 127.0.0.1 -p 5432 -U "$PG_USER" -d postgres` 装载 `load-postgresql.sql`，而该 SQL 全文（`rg 'CREATE DATABASE|\\connect|consilens_demo'` 零命中）只在当前连接库的 public schema 建表（`CREATE TABLE public.*`），没有创建或切换到 `consilens_demo` 库。五个 pg YAML 的 target URL 却全部是 `jdbc:postgresql://127.0.0.1:5432/consilens_demo?currentSchema=public`。在标准 PostgreSQL 下，psql 连接的是 `postgres` 库，表建在 postgres 库的 public schema；YAML 查询的是 consilens_demo 库，目标表（`consilens_performance_demo_table`/`daily_order_summary`/`fact_orders`/`users`/`orders`/`orders_backup`）全部不可见，五个用例目标端查询必然失败。实现者验证的库级扫描将 pg 整组"按 public schema 豁免"，恰好跳过了这个库级契约检查，属于验证盲区。
- 修复建议：三选一——(a) `load-postgresql.sql` 头部增加 `CREATE DATABASE consilens_demo;` + `\connect consilens_demo`（psql 元命令，脚本已用 psql 执行，可行）；(b) 脚本 pg 装载改为 `-d consilens_demo`（前提该库已存在）；(c) 将五个 pg YAML 的 URL 改为 `jdbc:postgresql://127.0.0.1:5432/postgres?currentSchema=public` 与装载库一致。推荐 (a) 或 (b)，并去掉库级扫描中 pg 的整组豁免、改为逐 YAML 校验。

### P1 / 中高

#### 2. 03 大表契约源端 `fact_orders` 表结构与目标端字段语义差异未验证

- 文件：`examples/mysql-presto/03-large-table.yaml:4-17`、`examples/mysql-presto/load-mysql.sql:146-156`、`examples/mysql-presto/load-presto-target.sql:151-160`
- 问题：03 现为源 `production.fact_orders`（`load-mysql.sql` 建表）、目标 `production_target.fact_orders`（`load-presto-target.sql` 建表）。两表列名/类型一致，目标端装载把 `total_amount` 计算为 `CAST(quantity*unit_price AS DECIMAL(18,4))`（源端装载同样计算，见 load-mysql.sql:155），字段契约一致、URL 库名一致，可运行。但该 03 为修复请求要求的新契约，无真实数据库验证其实际比对结果；且实现者披露"round-2 时点 03 实为 type: sql 查询 consilens_performance_demo_table、URL 一致可运行"与我的 round-2 报告描述（"URL 指向 consilens_demo_target 且查 fact_orders"）存在出入——我 round-2 的库级结论基于 grep URL，未完整读 03 的 resource 段，实现者的披露更准确，此点已澄清。当前契约成立但属于未联调的新增路径。
- 修复建议：有外部数据库时跑一次 presto/trino 03；无环境则保留为已知未联调项并在最终报告中披露（实现者已披露）。

### 已修复并独立复验通过的项（repair-request 七项逐项）

1. **presto/trino/sqlserver 02 daily_order_summary**：`load-presto-target.sql:172-189`、`load-trino-target.sql:172-189` 在 `consilens_demo_target` 库建表+装载（`DATE(created_at)` 聚合、`COALESCE` 处理 NULL）；`load-sqlserver.sql:214-242` 以 T-SQL 风格 `USE consilens_demo; GO` + `CAST(created_at AS DATE)` 建表+装载+validation；三个 02 YAML target 均为 `table daily_order_summary`，URL 分别为 `consilens_demo_target`/`consilens_demo`，与装载一致。源端聚合 SQL 与目标装载聚合口径（DATE/GROUP BY status）一致。
2. **presto/trino 03 fact_orders**：03 YAML 源 URL `mysql/production`、目标 URL `mysql/production_target`、resource `table fact_orders`，与 `load-mysql.sql:146`（`production.fact_orders`）、`load-presto-target.sql:151`/`load-trino-target.sql:151`（`production_target.fact_orders`）一致；keys/fields（order_id/customer_id/product_id/quantity/unit_price/total_amount/order_date/status）与两表列一致。实现者关于 round-2 时点 03 形态的披露与事实相符（我 round-2 仅按 URL 判库名，未读 resource 段；当时 03 可运行，P0-2 结论依据不充分，本次以现契约为准）。
3. **oceanbase 05**：资源名改为 `mydb.orders`/`mydb.orders_backup`（`05-same-db-join.yaml:10,20`），URL 保持 `consilens_demo`，与 `load-oceanbase.sql:127-142` 的 `mydb.orders*` 一致；与 starrocks 05 模式对齐。表级扫描通过。
4. **consilens.it.<NAME> 命名**：`testEnvironment()` 为 `System.getProperty("consilens.it." + name)`（name 即 `MYSQL_USER` 等大写，无 toLowerCase 映射），Javadoc 同步为 `consilens.it.<NAME>` 且举例 `-Dconsilens.it.MYSQL_USER=root`，文档与实现一致。
5. **Doris 端口**：`mysql-doris/0{1..5}.yaml` 全部 9030（6 处），脚本 `DORIS_PORT` 默认 9030（`run-comparison-test.sh:37`），脚本头已披露"按 Doris 默认 9030 假设，需人工确认 kuanilens/database 实际映射"。
6. **脚本头三条披露**：Trino/Presto `mysql` catalog 必须指向 127.0.0.1:13306 同一 MySQL 实例（`run-comparison-test.sh:39-41`）、Doris 9030 假设（42-43）、sqlcmd 临时文件替代 `/dev/stdin` 的原因（44-45），均已到位。
7. **sqlcmd {SQL_FILE}**：`load_target_data` 支持 `{SQL_FILE}` 占位符替换（`run-comparison-test.sh:185`），sqlcmd 调用改为 `-i {SQL_FILE}`（:282）；其余 loader 仍走 stdin 重定向分支。脚本逻辑正确，`bash -n` 通过。

### 交叉检查（独立复跑）

- 表级契约扫描（50 YAML target table ↔ 19 load SQL，我重写脚本复跑）：**TABLE_SCAN_PASS**（0 缺失）。
- 库级契约扫描（排除 pg 后复跑）：**DB_SCAN_PASS**；pg 整组被实现者豁免，但该豁免正是 P0-1 被掩盖的原因——我独立复跑时对 pg 逐文件检查才发现装载库断裂。
- `bash -n examples/run-comparison-test.sh`：pass。
- `git diff --check`：pass。
- `./mvnw -q -pl consilens-cli -am test-compile`：pass。
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest`（默认模式）：BUILD SUCCESS（参数化用例按条件跳过）。
- fat jar smoke：`java -jar` 打印 usage exit 0（62MB，未回退）。
- 旧路径残留：`build/libs|consilens-cli-1.0.0|gradlew|test.profile=integration` 无；`/dev/stdin` 仅剩脚本头披露注释。
- 凭据外置、`.gitignore`、shade 配置：round-2 已验证未回退（本轮 spot check 通过）。

## 实现者未覆盖的风险

1. **pg 装载库契约**（P0-1）：实现者的库级扫描对 pg 整组豁免，未发现 `psql -d postgres` + 无建库/切库语句 vs YAML `consilens_demo` 的断裂。这是本单元最需要补的验证盲区。
2. 02/03 新增表（`daily_order_summary`、`fact_orders` 大表）装载 DDL 无真实数据库执行验证，方言兼容性（尤其 sqlserver `GO` 批处理与 presto 经 mysql catalog 的 `DATE()` 聚合）未确认。
3. Trino/Presto catalog 指向、Doris 端口 9030 均依赖外部 compose，已披露但无法验证。

## Validation 评估

实现者的验证证据总体可信且大部分可复现（我独立复跑表级/库级扫描、bash -n、test-compile、集成测试默认模式、fat jar smoke 均通过或相符）。但 validation.md 中"pg 按 public schema 豁免"的库级扫描规则掩盖了 P0-1，属于验证设计缺陷而非实现者造假；按 medium-dev-loop 默认信任规则，此情况应重新评估扫描逻辑并修复装载库契约。

## 是否重新运行测试

yes（低成本定向验证）：

- 表级契约扫描（独立重写脚本）：pass
- 库级契约扫描（独立重写脚本，pg 逐文件核对）：发现 P0-1；其余组 pass
- `bash -n examples/run-comparison-test.sh`：pass
- `git diff --check`：pass
- `./mvnw -q -pl consilens-cli -am test-compile`：pass
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest`：pass（默认模式）
- `java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar`：pass
- 残留扫描（build/libs、gradlew、test.profile=integration、/dev/stdin）：pass

## 已尝试的风险场景

1. 逐 pair 核对 YAML target URL 库名 vs 装载 SQL 限定符（含 pg）→ 发现 P0-1。
2. 逐 YAML 核对 target table 资源名（含 `mydb.` 前缀）vs 装载 SQL 建表 → 全部通过。
3. 03 大表源/目标字段集核对 → 一致。
4. `consilens.it.<NAME>` 大小写与 Javadoc 一致性核对 → 一致。

## Required Changes

1. （P0）修复 mysql-pg 装载库契约：`load-postgresql.sql` 增加 `CREATE DATABASE consilens_demo; \connect consilens_demo`，或将脚本 psql 改为 `-d consilens_demo`（前置建库），或将五个 pg YAML URL 改为与装载库一致；并去掉库级扫描的 pg 整组豁免、改为逐 YAML 校验。
2. （P1）02/03 新增装载路径（sqlserver `GO` 批处理、presto/trino `DATE()` 聚合）在真实 PostgreSQL/MySQL 环境验证或明确列为未联调项（实现者已披露，需保留在最终报告）。

## 接受的非阻塞缺口

- round-2 报告中 P2 项（`load_mysql_data` 错误吞掉、`sqlcmd -i /dev/stdin`）已处理或可接受；`sqlcmd` 已改 `{SQL_FILE}`。
- Doris 端口 9030 为默认值假设，外部 compose 无法验证，已充分披露，接受为待确认项。
- `consilens.it.<NAME>` 命名与 Javadoc 一致，无遗留。
- round-2 的 P0-2（03 查错库）经实现者披露与我复核确认：round-2 时点 03 实为 type: sql 查询 `consilens_performance_demo_table`（可运行），我的 round-2 报告该条依据不充分；本次已按修复请求意图统一为 fact_orders 大表契约且现契约成立。

## 备注

- 本报告全部证据基于仓库内只读检查；未修改任何源码/测试/事实源文件，未 stage/commit/push。
- 外部数据库（kuanilens/database compose）不在仓库内，pg 装载库断裂的实机影响、Doris 端口、Trino/Presto catalog 均无法实机验证，按披露要求记录。
