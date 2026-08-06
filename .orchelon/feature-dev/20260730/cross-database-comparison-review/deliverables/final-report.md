# 最终交付报告：跨数据库比较功能修复（medium-dev-loop）

## 结果

**complete**：medium-dev-loop 开发循环完整执行（Review → 修复 → 复审 ×4 轮），两个实现单元最终均通过独立复审（pass）。跨数据库比较的 SQL 生成语义与示例集成入口已修复，未 stage/commit/push，工作区变更保留待用户审阅。

## 任务背景与范围

- 仓库：`/Users/szh/soft/consilens`，分支 `dev6`，基线 `4bbbd1f`。
- 主题：跨数据库比较功能（MySQL 与 Oracle/Presto/Trino/ClickHouse/Doris/SQL Server/PostgreSQL/OceanBase/TiDB/StarRocks 的 checksum 与明细比较）的代码 Review 与修复开发。
- 两个实现单元：
  - UNIT-SQL-GENERATION：SQL 生成器、数据类型规范化、分段方言、相关测试。
  - UNIT-CROSS-DB-EXAMPLES：CLI 产物、集成测试、运行脚本、10 组示例 YAML/SQL、凭据外置。

## 单元与 Review 结论

| 单元 | 轮次 | 结论 | 关键修复 |
|---|---|---|---|
| UNIT-SQL-GENERATION | round-1 | fail | Oracle XML 标签进 hash、Doris 进位不传播、ClickHouse NULL 丢失、Presto 尾零丢失、SQL Server 日期字面量、5 个测试断言未同步 |
| UNIT-SQL-GENERATION | round-2 | fail | Oracle 聚合尾部 `|` 仍与 MySQL 不一致、Oracle diff_columns NULL 传播、Doris DOUBLE 中间精度损失 |
| UNIT-SQL-GENERATION | round-3 | **pass** | Oracle 聚合去尾 `|` + 空表 COALESCE、Oracle diff_columns 重写（部分列差异非 NULL）、Doris 去 DOUBLE、DBMS_CRYPTO 授权说明 |
| UNIT-CROSS-DB-EXAMPLES | round-1 | fail | 脚本 Gradle 路径/薄 jar、目标装载缺失、两处引号错位、Presto 库名、明文凭据、profile 误导 |
| UNIT-CROSS-DB-EXAMPLES | round-2 | fail | 02 聚合表缺失、03 库名错位、oceanbase 05 表库错位、`consilens.it.*` 大小写、Doris 端口不一致 |
| UNIT-CROSS-DB-EXAMPLES | round-3 | fail | mysql-pg 装载库（postgres）与查询库（consilens_demo）不一致 |
| UNIT-CROSS-DB-EXAMPLES | round-4 | **pass** | pg 条件建库 + `\connect` 切库，无豁免库级契约扫描 FAILURES=0 |

## 修改文件（未 stage/commit）

### UNIT-SQL-GENERATION（connector 与 core）

- `consilens-connector/.../oracle/OracleSqlQueryGenerator.java`：checksum 聚合改 `COALESCE(RTRIM(EXTRACT(XMLAGG(...), '//text()').GETCLOBVAL(), '|'), '')`，与 MySQL `GROUP_CONCAT(...SEPARATOR '|')` 逐字节一致；`buildDiffColumnsExpression` 独立重写消除 `||` NULL 传播；javadoc 补充 `GRANT EXECUTE ON sys.dbms_crypto`。
- `consilens-connector/.../doris/DorisDataTypeHandler.java`：`normalizeDecimal` 直接对 DECIMAL `ROUND/TRUNCATE` 后 `DECIMAL(38, min(p,30))` 定标，进位传播、移除 `Math.pow` 溢出路径、无 DOUBLE 中间转换。
- `consilens-connector/.../clickhouse/ClickHouseSqlQueryGenerator.java`：checksum/row_hash 的 NULL 整数 `COALESCE(toString(...), '0')`。
- `consilens-connector/.../presto/PrestoDataTypeHandler.java`：DOUBLE/FLOAT/DECIMAL `FORMAT('%.Nf', ...)` 固定小数位。
- `consilens-core/.../segment/TableSegment.java`：sqlserver/mssql 输出带引号日期字符串，其余方言保留 ANSI 字面量，`T` 分隔符归一化。
- 测试：Oracle/ClickHouse/Doris/Presto/Trino 既有断言同步 + 进位/NULL/尾零/部分列差异定向用例；TableSegmentTest 方言用例；两个 OceanBase 测试 target 统一为 `orders_backup`。

### UNIT-CROSS-DB-EXAMPLES（CLI 与示例）

- `consilens-cli/pom.xml`：maven-shade-plugin 3.5.3 fat jar（含签名文件排除）。
- `CrossDatabaseComparisonIntegrationTest.java`：`@EnabledIfSystemProperty("consilens.it.enabled")` 真实启用机制、稳定 examples 定位、22 个凭据外置（`consilens.it.<NAME>` 大写契约）。
- `examples/run-comparison-test.sh`：Maven 产物 JAR 路径、10 目标 best-effort 装载、端口参数化、凭据 fail-fast、脚本头披露（Trino/Presto catalog、Doris 端口、sqlcmd 方案）。
- `examples/mysql-*`：两处引号错位修复；Presto/Trino 01/03 target 库统一；02 聚合表按方言补齐（presto/trino/sqlserver）；oceanbase 05 `mydb.` 前缀；Doris 9031→9030；pg 条件建库 + 切库。
- `examples/configs/{cross-db,same-db}`：OceanBase YAML 凭据 `${env.*}` 占位符。
- `.gitignore`：`examples/mysql-doris/*.bak`。

## 验证记录（最终轮）

- `mvn -pl <oracle,clickhouse,doris,presto,trino> -am test` — pass（184 个测试）
- `mvn -pl <mysql,postgresql,oceanbase,base> -am test` — pass（169 个测试，未破坏既有比较语义）
- `mvn -pl consilens-core -am test -Dtest=TableSegmentTest` — pass（17/17）
- `./mvnw -pl consilens-cli -am test-compile` — pass
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest` — pass（3 run, 1 skipped）
- fat jar `java -jar` + `config validate` ×4 — pass
- 无豁免库级/表级契约扫描（50 YAML × 19 load SQL）— FAILURES=0（Reviewer 独立复跑）
- `bash -n examples/run-comparison-test.sh` — pass；`git diff --check` — pass

## 剩余风险（如实披露）

- Oracle `EXTRACT/RTRIM/DBMS_CRYPTO`、Doris `DECIMAL(38,30)`、Presto `FORMAT`、pg `\gexec`/`\connect` 均为语法/语义推断，未在真实数据库联调；建议有环境时执行 MySQL↔Oracle/Presto/ClickHouse/Doris checksum 对照与示例脚本单 pair 运行。
- Doris FE 查询端口按 9030 假设统一，外部 compose 不在仓库，需人工确认。
- Trino/Presto 的 `mysql` catalog 必须指向 13306 MySQL 实例，前提已在脚本头披露。
- `consilens-core` 全量测试 31 个 Mockito 环境失败与 2 个 OceanBase 外部库不可达为环境性失败；`./mvnw -pl consilens-cli -am test` 的上游 Oracle connector 测试已随 round-3 修复，建议全链路重跑一次确认。
- 两个 OceanBase 比较测试与 `CrossDatabaseDockerITest` 中的明文密码为基线 `4bbbd1f` 既有代码，非本次引入；建议后续外置为环境变量。
- `output/presto-orders-diff.csv` 为验证产物（仅表头），如需保留请移入正式目录，否则可删除。

## 交付物说明

- 单元 Review：`../units/UNIT-SQL-GENERATION/reviews/{round-1,round-2,round-3}/review.md`、`../units/UNIT-CROSS-DB-EXAMPLES/reviews/{round-1,round-2,round-3,round-4}/review.md`。
- 修复请求：两个单元 `iterations/round-{2,3,4}/repair-request.md`。
- 汇总结论：`../review-summary.md`；集成清单：`../integration-checklist.md`。
