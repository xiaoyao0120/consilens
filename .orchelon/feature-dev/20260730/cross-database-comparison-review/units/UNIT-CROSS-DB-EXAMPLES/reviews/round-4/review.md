# UNIT-CROSS-DB-EXAMPLES 第 4 轮 Review

## Verdict

**pass**

## Confidence

High

## Risk Level

Medium

## 风险等级理由

round-4 修复针对 round-3 的 P0（mysql-pg 装载库契约），修复方案正确、语义自洽且经独立复验：`load-postgresql.sql` 头部以 `\gexec` 条件建库 `consilens_demo` 并 `\connect consilens_demo`，脚本 psql 以 `-d postgres` 起始连接后由 SQL 内部切库，五个 pg YAML URL 与装载库一致；无豁免库级契约扫描脚本 `/tmp/db-contract-scan-r4.sh` 存在且独立复跑 FAILURES=0（pg 不再豁免）；表级扫描 FAILURES=0；`bash -n`、`git diff --check`、`test-compile`、集成测试默认模式全部通过；round-2/3 已验证成果（shade fat jar、脚本 JAR 路径、凭据外置、.gitignore、集成测试启用机制、02/03/05 契约、`consilens.it.<NAME>`、Doris 9030、sqlcmd `{SQL_FILE}`）无回退。唯一剩余风险是外部数据库未联调（psql 元命令、各方言装载 DDL 需真实环境验证），已充分披露，属接受的非阻塞缺口。

## 已读文件

### 事实源

- `/Users/szh/.agents/skills/medium-dev-loop/SKILL.md`（此前已完整阅读，本轮按需回看 Verdict/裁决规则）
- 仓库根 `AGENTS.md`（文件不存在；以对话提供的十二条铁律为准）
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/task-brief.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/implementation-plan.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/work-breakdown.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/run-state.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/scope.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/reviews/round-3/review.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/iterations/round-4/repair-request.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/change-summary.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/validation.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/resume-state.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/worklog.md`

### 审查范围内文件

- `examples/mysql-pg/load-postgresql.sql`（头部建库/切库段完整读，全文件库名扫描）
- `examples/mysql-pg/0{1..5}.yaml`（URL 库名全量）
- `examples/run-comparison-test.sh`（pg 装载段、脚本头披露、sqlcmd 段）
- `/tmp/db-contract-scan-r4.sh`（完整阅读 + 独立复跑）
- `consilens-cli/src/test/java/com/consilens/cli/config/CrossDatabaseComparisonIntegrationTest.java`（`consilens.it.<NAME>` 段）
- `examples/mysql-presto/load-presto-target.sql`、`examples/mysql-trino/load-trino-target.sql`、`examples/mysql-sqlserver/load-sqlserver.sql`（02 daily_order_summary 存在性 + 03 fact_orders）
- `examples/mysql-presto/03-large-table.yaml`、`examples/mysql-trino/03-large-table.yaml`、`examples/mysql-oceanbase/05-same-db-join.yaml`、`examples/mysql-doris/0{1..5}.yaml`（无回归 spot check）

### 抽样说明

50 个 YAML × 19 个 load SQL 经无豁免库级扫描与表级扫描全覆盖（独立复跑）；pg/presto/trino/sqlserver/oceanbase/doris 高风险文件完整阅读；其余组由扫描覆盖。未逐行阅读重复 INSERT 数据段。

## 逐项发现（按严重度排序）

### P0 修复验证（round-3 阻断项）

#### 1. mysql-pg 装载库契约已修复，方案 a 语义自洽

- 文件：`examples/mysql-pg/load-postgresql.sql:14-22`
- 修复内容：头部新增条件建库 `SELECT 'CREATE DATABASE consilens_demo' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'consilens_demo')\gexec`（`\gexec` 把查询结果作为 SQL 执行，库已存在时结果集为空则不执行，幂等）与 `\connect consilens_demo`（psql 元命令切换连接库，9.6+ 支持 `\gexec`，符合文档语义）。此后 `CREATE TABLE public.*` 落在 `consilens_demo` 库 public schema。
- 装载链路自洽：`run-comparison-test.sh:258` 仍以 `psql -h 127.0.0.1 -p 5432 -U "$PG_USER" -d postgres` 起始连接（postgres 库通常存在且无需建库权限的数据库存在，`\gexec` 建库需 PG_USER 有 CREATEDB 权限——脚本未披露该前提，但本地示例环境 root/superuser 常见，属非阻断披露缺口），随后 SQL 内部切到 `consilens_demo`。
- YAML 一致：五个 pg YAML target URL 全部为 `jdbc:postgresql://127.0.0.1:5432/consilens_demo?currentSchema=public`，与装载库一致（round-3 的断裂已消除）。
- 结论：P0 修复成立。

#### 2. 无豁免库级契约扫描已落地且独立复跑通过

- 文件：`/tmp/db-contract-scan-r4.sh`（实现者提供，4778 字节，覆盖全部 10 组逐 YAML 校验）
- 扫描规则审查：mysql-pg 分支校验 `consilens_demo` 出现在装载 SQL 的 `CREATE DATABASE`/`\connect` 且 target table 以 `public.<tbl>` 存在（无整组豁免）；sqlserver 校验 `databaseName` ∈ `USE`/`CREATE DATABASE`；oracle 校验 SID（ORCL）出现在装载 SQL 连接说明；其余组校验 URL 库与表限定符。逻辑完整，无豁免盲区。
- 独立复跑：`bash /tmp/db-contract-scan-r4.sh` 输出 `----` + `FAILURES=0`（无任何 URL-DB-UNPARSEABLE / *_MISSING 行），与实现者记录一致。

#### 3. 表级契约扫描独立复跑仍 FAILURES=0

- 我重写等价表级扫描（50 YAML target table 资源名含 `mydb.` 前缀 ↔ 19 个目标 load SQL 建表/装载），输出 `TABLE_SCAN_PASS`，无缺失。

### 无回归检查（round-2/3 成果）

1. **02 daily_order_summary**：presto/trino `load-presto-target.sql`/`load-trino-target.sql` 各 6 处 `daily_order_summary`（`consilens_demo_target` 库建表+装载+validation），sqlserver `load-sqlserver.sql` 7 处（`USE consilens_demo` + `CAST(created_at AS DATE)` 建表装载），未回退。
2. **03 fact_orders 大表契约**：presto 03 URL `jdbc:presto://127.0.0.1:8085/mysql/production_target`（:13）、trino 03 URL `jdbc:trino://127.0.0.1:8081/mysql/production_target`（:17），源 `mysql/production`，与 `production_target.fact_orders` 装载一致，未回退。
3. **oceanbase 05**：`name: mydb.orders`（:10）、`name: mydb.orders_backup`（:20），URL 保持 `consilens_demo`，与 `load-oceanbase.sql` 的 `mydb.orders*` 一致，未回退。
4. **consilens.it.<NAME>**：测试类 `System.getProperty("consilens.it." + name)`（name 为 `MYSQL_USER` 等大写，无 toLowerCase），Javadoc 举例 `-Dconsilens.it.MYSQL_USER=root`，文档与实现一致，未回退。
5. **Doris 9030**：`examples/mysql-doris/0{1..5}.yaml` 全部 9030（6 处），与脚本 `DORIS_PORT` 默认 9030 一致，未回退。
6. **脚本头披露**：Trino/Presto catalog 指向 13306 MySQL（`run-comparison-test.sh:39-41`）、Doris 9030 假设（42-43）、sqlcmd 临时文件说明（44-45）均在，未回退。
7. **sqlcmd {SQL_FILE}**：`load_target_data` 占位符替换（:185）+ `sqlcmd ... -i {SQL_FILE}`（:282）在，未回退。
8. **shade fat jar / JAR 路径 / 凭据外置 / .gitignore / 集成测试启用机制**：`java -jar` smoke 通过（usage exit 0）、残留扫描无旧路径、凭据无明文（未重扫但 round-3 已验且本单元仅改 pg SQL 头部，风险极低）、`.gitignore` 忽略 `.bak` 生效（未回退）。

### 交叉检查（独立复跑）

- `bash -n examples/run-comparison-test.sh`：pass
- `git diff --check`：pass
- `./mvnw -q -pl consilens-cli -am test-compile`：pass
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest`（默认模式）：pass（BUILD SUCCESS，参数化用例按条件跳过）
- 残留扫描：`build/libs|consilens-cli-1.0.0|gradlew|test.profile=integration` 无命中；`/dev/stdin` 仅剩脚本头披露注释
- 已知既有失败：`AiConfigCommandTest` 等 3 个 AI 命令测试与 `OracleSqlQueryGeneratorTest` 均属既有/其他单元范围，不判本单元 fail

## 实现者未覆盖的风险

1. **psql 元命令真实执行未验证**：本机无 psql/PostgreSQL 容器，`\gexec` 条件建库 + `\connect` 切换的实机行为（尤其 PG_USER 需 CREATEDB 权限、`\gexec` 在 9.6+ 可用性）按文档判断正确但未实跑。建议有 PostgreSQL 环境时跑一次 `psql -f examples/mysql-pg/load-postgresql.sql` 验证幂等性。
2. **PG_USER 权限前提未在脚本头披露**：`\gexec` 建库要求 PG_USER 具备 CREATEDB 权限；脚本头未注明。非阻断（本地示例通常用 superuser），建议补充披露。
3. **外部数据库未联调**：10 对示例的实际装载/比较未在真实环境执行；Doris 9030、Trino/Presto catalog 指向 13306 依赖外部 compose，已披露。

## Validation 评估

实现者验证记录与独立复跑结果完全一致：无豁免库级扫描 FAILURES=0（脚本存在、逻辑审查通过、独立复跑通过）、表级扫描 FAILURES=0、`bash -n` pass、`test-compile` pass、集成测试 BUILD SUCCESS、`git diff --check` pass。未发现验证与实现不符之处；round-4 修复范围（仅 pg 装载 SQL 头部 + 扫描脚本）与声明一致。

## 是否重新运行测试

yes（低成本定向验证，全部独立复跑）：

- `bash /tmp/db-contract-scan-r4.sh` — pass（FAILURES=0）
- 表级契约扫描（独立重写脚本）— pass（TABLE_SCAN_PASS）
- `bash -n examples/run-comparison-test.sh` — pass
- `git diff --check` — pass
- `./mvnw -q -pl consilens-cli -am test-compile` — pass
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest` — pass（默认模式）
- `java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` — pass（usage exit 0）
- 残留扫描 — pass

## 已尝试的风险场景

1. pg 装载库 vs 五个 pg YAML URL 逐 YAML 校验 → 一致（`consilens_demo` 被建库/切库）。
2. 无豁免库级扫描全量复跑 → FAILURES=0。
3. 表级契约（含 `mydb.` 前缀、`fact_orders`、`daily_order_summary`）全量复跑 → 无缺失。
4. round-3 七项修复逐一 spot check → 无回退。
5. psql 元命令语义（`\gexec` 幂等条件执行、`\connect` 切换）按 PostgreSQL 文档审查 → 正确。

## Required Changes

无（阻塞项全部消除；以下为非阻塞建议，不构成返修条件）：

1. （建议）脚本头补充 PG_USER 需 CREATEDB 权限的前提，或在 pg 装载段加错误提示。
2. （建议）有 PostgreSQL 环境时验证 `psql -f load-postgresql.sql` 的建库/切库幂等性，并纳入最终报告的剩余风险。

## 接受的非阻塞缺口

- pg `\gexec`/`\connect` 未实机验证（本机无 psql），按文档语义判断正确；已披露，接受。
- PG_USER CREATEDB 权限前提未披露（本地示例通常满足）；建议补充，不阻断。
- 外部数据库未联调、Doris 9030 假设、Trino/Presto catalog 前提：均已在脚本头与最终报告披露，接受为待外部环境确认项。
- 既有 AI 命令测试与上游 Oracle connector 测试失败：归属其他单元/既有问题，不影响本单元。

## 备注

- 本报告全部证据基于仓库内只读检查；未修改任何源码/测试/事实源文件，未 stage/commit/push。
- 这是 UNIT-CROSS-DB-EXAMPLES 的第四个（最后）Review 轮次；本单元四个轮次的修复请求均已闭环：round-1（8 项）→ round-2（3 项 P0）→ round-3（1 项 P0）→ round-4（pg 装载库契约）全部解决。
