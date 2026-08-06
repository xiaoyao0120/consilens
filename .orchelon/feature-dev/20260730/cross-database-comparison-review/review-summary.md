# Review 汇总（round-1 至 round-4）

## 实现单元结果

| 单元 | 最终轮次 | Risk Level | Reviewer | 最终验收 | 状态 |
|---|---:|---|---|---|---|
| UNIT-SQL-GENERATION | round-3 | High → Medium | 独立 Reviewer（Banach） | pass | pass |
| UNIT-CROSS-DB-EXAMPLES | round-4 | High → Medium | 独立 Reviewer（Cicero） | pass | pass |

## 轮次历程

- round-1：两个单元均 fail（Oracle XML 标签进 hash、Doris 进位不传播、ClickHouse NULL 丢失、Presto 尾零丢失、SQL Server 日期字面量、5 个测试断言未同步；脚本 JAR 路径、目标装载缺失、引号错位、库名不一致、明文凭据、profile 误导等）。
- round-2：两个单元均 fail（Oracle 聚合尾部 `|` 仍与 MySQL 不一致、Oracle diff_columns NULL 传播、Doris DOUBLE 精度损失；presto/trino/sqlserver 02 表缺失、presto/trino 03 库名、oceanbase 05 表库错位等）。
- round-3：UNIT-SQL-GENERATION pass（Oracle 去尾 `|`+空表 COALESCE、Oracle diff_columns 重写、Doris 去 DOUBLE、DBMS_CRYPTO 授权说明）；UNIT-CROSS-DB-EXAMPLES fail（新发现 mysql-pg 装载库 postgres 与查询库 consilens_demo 不一致）。
- round-4：UNIT-CROSS-DB-EXAMPLES pass（pg 条件建库+切库，无豁免库级契约扫描 FAILURES=0）。

## 最终集成验证

- 已检查契约：
  - `stringJoin` 扩展点（Base CONCAT_WS / Presto+Trino ARRAY_JOIN / Oracle ||）与 `JoinDiffer` 下游解析兼容。
  - 数值规范化跨库一致性（MySQL FORMAT 基准 vs Presto/Doris/ClickHouse 新实现）。
  - TableSegment 日期字面量的方言兼容性。
  - YAML 字段与配置模型 `@JsonProperty` 映射、50 个 YAML 占位符与脚本导出变量一一对应。
  - 重命名路径残留（`oceanbase-test`、`mysql-init`、`orders_backup`）。
  - 测试发现规则（surefire、`@Disabled`、profile 是否存在）。
- 已运行命令：
  - `bash -n examples/run-comparison-test.sh` — pass
  - `git diff --check` — pass
  - `mvn -pl <oracle,clickhouse,doris,presto,trino> -am test` — pass（184 个测试，round-3 Reviewer 复跑）
  - `mvn -pl <mysql,postgresql,oceanbase,base> -am test` — pass（169 个测试）
  - `mvn -pl consilens-core -am test -Dtest=TableSegmentTest` — pass（17/17）
  - `./mvnw -q -pl consilens-cli -am test-compile` — pass
  - `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest` — pass（3 run, 1 skipped）
  - `java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` — pass（fat jar usage，exit 0；`config validate` ×4 pass）
  - 无豁免库级/表级契约扫描（50 YAML × 19 load SQL）— FAILURES=0（round-3/round-4 Reviewer 独立复跑）
- 已通过检查：
  - 受影响模块编译、ClickHouse 0.9.8 依赖解析、脚本语法、diff 空白检查、YAML 结构一致性、旧路径无残留（除 docker-compose 挂载）。
- 失败检查：
  - consilens-core 全量 31 个 Mockito MockMaker 环境失败、2 个 OceanBase 测试外部库（2881/13306）不可达——均为环境性失败，非本次改动引入。
  - `./mvnw -pl consilens-cli -am test` 被上游 `consilens-connector-oracle` 的既有测试阻断——已随 UNIT-SQL-GENERATION round-3 修复，单模块跑通；全链路未复跑。
  - `./mvnw -pl consilens-cli test` 中 3 个 Ai 命令测试与 2 个 `ExampleConfigurationCompatibilityTest`（历史 YAML 缺 `ORACLE_USER`）为既有失败，非本次引入。
- 手工检查：
  - `examples/mysql-oceanbase/load-oceanbase.sql:103` 与 `examples/mysql-presto/load-presto-target.sql:105` 引号错位复现。
  - `deploy/mysql-init/01-seed-data.sql` 已跟踪、`deploy/docker-compose.yml` 挂载 `./mysql-init` 相对 deploy/ 解析失效。
  - 全仓无 `orders_target` 建表 SQL；两个 OceanBase 测试引用不存在的表。
  - `examples/mysql-doris/` 6 个 `.bak` 未跟踪且与正式文件有实质差异。
- Review 后 Diff：无产品代码变化（两个 Reviewer 均未修改）。
- 剩余风险：
  - Oracle `EXTRACT/RTRIM/DBMS_CRYPTO`、Doris `DECIMAL(38,30)`、Presto `FORMAT`、pg `\gexec`/`\connect` 均未在真实数据库联调（本机无外部数据库环境）。
  - Doris FE 查询端口按 9030 假设统一，外部 compose 不在仓库，需人工确认。
  - Trino/Presto `mysql` catalog 必须指向 13306 MySQL 实例，前提已披露。
  - 两个 OceanBase 测试与 Docker ITest 中的明文密码为基线既有代码（4bbbd1f），不在本次修复范围，建议后续外置。
  - 全部跨库比较均为静态/SQL 层验证，未连真实数据库。

## 已阻塞或跳过范围

- 无阻塞。外部数据库集成测试、真实跨库连接未运行（无外部环境，任务约束）。

## 总体结论

**partial**：Review 流程完整执行（事实源、双单元、独立 Reviewer、定向验证、集成检查、修复请求），但两个单元均未通过 Review，存在多项 P0/P1 缺陷，当前工作区变更不可视为可交付。
