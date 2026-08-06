# UNIT-CROSS-DB-EXAMPLES 第 2 轮 Review

## Verdict

**fail**

## Confidence

High

## Risk Level

High

## 风险等级理由

八项修复请求中的 1、2、3、5、6、7、8 已实质修复并有可复现证据，但交叉检查发现 repair-request 第 4 项要求核对的契约范围内仍存在 4 处会导致示例必然失败的 YAML/SQL 契约断裂：presto/trino/sqlserver 的 02 用例引用不存在的 `daily_order_summary` 表（target 装载 SQL 未创建），presto/trino 的 03 用例查询 `consilens_demo_target` 库中不存在的 `fact_orders` 表，oceanbase 05 用例的 `orders`/`orders_backup` 表与装载库不匹配。这些属于"YAML/SQL 契约不一致导致示例必然失败"的阻断缺陷，故判 fail。

## 已读文件

### 事实源

- `/Users/szh/.agents/skills/medium-dev-loop/SKILL.md`（完整）
- 仓库根 `AGENTS.md`（文件不存在；以对话提供的十二条铁律指令为准）
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/task-brief.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/implementation-plan.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/work-breakdown.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/run-state.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/scope.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/reviews/round-1/review.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/iterations/round-2/repair-request.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/change-summary.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/validation.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/worklog.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/resume-state.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/integration-checklist.md`

### 审查范围内文件（100% 检查或契约扫描）

- `consilens-cli/pom.xml`（完整 diff + 当前文件）
- `consilens-mcp/pom.xml`（shade 参考，完整）
- `consilens-cli/src/test/java/com/consilens/cli/config/CrossDatabaseComparisonIntegrationTest.java`（完整）
- `examples/run-comparison-test.sh`（完整）
- `examples/mysql-*/0{1..5}.yaml`（50 个，URL/库名/资源名契约扫描 + 关键用例完整读）
- `examples/mysql-*/load-*.sql`（19 个，装载库/表契约扫描 + 两处修复行完整读）
- `examples/configs/cross-db/mysql-to-oceanbase-cross-db.yaml`、`examples/configs/same-db/oceanbase-same-db.yaml`
- `.gitignore`（diff + 当前文件）
- `consilens-dist/src/main/assembly/assembly.xml`、`consilens-dist/pom.xml`（shade 对 dist 影响核对）
- `deploy/docker-compose.yml`、`deploy/mysql-init/01-seed-data.sql`（挂载与既有引用核对）

### 抽样说明

10 组 `mysql-*` 的 50 个 YAML 通过自动契约扫描覆盖（URL 库名、资源表名、`${env.*}` 占位符）；presto/trino/sqlserver/oceanbase/doris 的 02/03/05 高风险用例完整阅读。未逐行阅读全部重复 INSERT 数据段（低风险生成物）。

## 逐项发现（按严重度排序）

### P0 / 阻断

#### 1. Presto/Trino/SQL Server 的 02 用例引用不存在的 `daily_order_summary` 表（YAML/SQL 契约断裂）

- 文件：`examples/mysql-presto/02-detail-to-aggregate.yaml:31`、`examples/mysql-trino/02-detail-to-aggregate.yaml:27`、`examples/mysql-sqlserver/02-detail-to-aggregate.yaml:26`
- 问题：三个 YAML 的 target `resource.type: table, name: daily_order_summary`，但 `load-presto-target.sql`、`load-trino-target.sql`、`load-sqlserver.sql` 全文均无 `daily_order_summary` 建表（`grep -c` 均为 0）；源端 `load-mysql.sql` 也不创建该表。pg/clickhouse/tidb 的 02 用例目标表存在且装载一致（`load-postgresql.sql:259`、`load-clickhouse.sql:206`、`load-tidb.sql:110`），说明这是修复遗漏而非设计如此。该用例在真实环境必然因目标表不存在而失败。
- 修复建议：为 presto/trino/sqlserver 的 target 装载 SQL 增加 `daily_order_summary` 建表+装载（与 pg 的 `load-postgresql.sql:259-268` 模式一致），或把 02 YAML target 改为 `type: sql` 聚合查询（与 starrocks/oceanbase/doris 的 02 模式一致）。

#### 2. Presto/Trino 的 03 用例 target 表 `fact_orders` 在 `consilens_demo_target` 库中不存在（YAML/SQL 契约断裂）

- 文件：`examples/mysql-presto/03-large-table.yaml:15`（URL 指向 `mysql/consilens_demo_target`）、`examples/mysql-trino/03-large-table.yaml:19`（URL 指向 `mysql/consilens_demo_target`）
- 问题：`load-presto-target.sql` / `load-trino-target.sql` 中 `fact_orders` 只建在 `production_target` 库（`load-presto-target.sql:151`、`load-trino-target.sql:151`），而 03 YAML 的 URL 指向 `consilens_demo_target` 且 resource 为 table `fact_orders`（未加库前缀）。pg/clickhouse 的 03 用例指向 `consilens_demo`（其装载 SQL 在 `consilens_demo.fact_orders` 建表，一致）；tidb 03 的 URL 直接指向 `production` 库（其装载在 `production.fact_orders`，一致）。presto/trino 03 查库必然失败。注意本项与 repair-request 第 4 项直接相关：01/04/05 的库名已修正，03 修正后与 `fact_orders` 实际所在库 `production_target` 仍然不一致。
- 修复建议：将 03 YAML 的 URL 改为 `jdbc:presto://127.0.0.1:8085/mysql/production_target`（trino 同理 `8081/mysql/production_target`），与装载库一致。

#### 3. OceanBase 05 用例 `orders`/`orders_backup` 表库名与装载库不匹配

- 文件：`examples/mysql-oceanbase/05-same-db-join.yaml:4,14`（URL 均指向 `consilens_demo`，resource 为 `orders`/`orders_backup`）
- 问题：`load-oceanbase.sql` 在 `mydb` 库装载 `orders`/`orders_backup`（`load-oceanbase.sql:127-142`），YAML 的 URL 却指向 `consilens_demo` 且表名未加库前缀，实际查询 `consilens_demo.orders` 必然不存在。对比同族 doris/starrocks 的 05：doris 05 URL 指向 `mydb`（`load-doris.sql` 在 mydb 建表，一致），starrocks 05 URL 指向 `consilens_demo` 但资源名显式写 `mydb.orders`（一致）。oceanbase 05 两处都错位，该用例必然失败。
- 修复建议：将 05 YAML 两处 URL 改为 `jdbc:mysql://127.0.0.1:2881/mydb`，或把资源名改为 `mydb.orders` / `mydb.orders_backup`（推荐后者，与 starrocks 05 模式一致）。

#### 4. OceanBase 04 用例 target 库与源库一致（契约提示，非阻断）

- 文件：`examples/mysql-oceanbase/04-mapped-checksum.yaml:19-25`
- 问题：target 类型为 oceanbase、URL 指向 `consilens_demo`，其 SQL 查询 `consilens_performance_demo_table`；`load-oceanbase.sql` 在 `consilens_demo` 库装载了该表，用例可运行。但同族 presto/trino 的 04 已统一指向 `mydb_target`/`users`（正确），oceanbase 04 与 01/02/03 一样实际查目标端同库同表，依赖 `load-oceanbase.sql` 装载的差异数据，可行但语义与 04 模板（映射字段）不一致。列为 P1 提示，不阻断。

### P1 / 高

#### 5. 集成测试 `consilens.it.<name>` 系统属性与脚本环境变量名契约：大小写不一致

- 文件：`CrossDatabaseComparisonIntegrationTest.java:135-141`（`testEnvironment()`）、`examples/run-comparison-test.sh:79-99`
- 问题：测试类 Javadoc 声明凭据可经 `consilens.it.<name>` 系统属性注入（name 对应环境变量名），实际实现把系统属性名映射为 `consilens.it.` + 环境变量名小写（如 `consilens.it.mysql_user`），而脚本注入的 YAML 占位符读取的是同名环境变量（`MYSQL_USER`）。测试侧系统属性名 `consilens.it.mysql_user`（全小写）与修复请求描述的 `consilens.it.<name>`（name 与环境变量名对应）在大小写上不一致，文档与实现有出入，易误导使用者。
- 修复建议：统一为 `consilens.it.MYSQL_USER`（与 env 名一致）或 `consilens.it.mysql_user`，并让 Javadoc 与实际实现一致；建议优先支持 `consilens.it.MYSQL_USER` 以对齐"与脚本环境变量名对应"的契约。

#### 6. 脚本 `mysql-trino`/`mysql-presto` 目标装载使用 MySQL 客户端直连，绕过 Trino/Presto

- 文件：`examples/run-comparison-test.sh:203-210`
- 问题：`load_target_data ... env MYSQL_PWD="$MYSQL_PASSWORD" mysql -h 127.0.0.1 -P 13306 -u "$MYSQL_USER"` 装载的是 MySQL 源库实例（同一 MySQL 的 `_target` 库），依赖 compose 中 MySQL 同时是 Trino/Presto 的 catalog 后端。该实现可用，但要求外部 compose 中 Trino/Presto 的 `mysql` catalog 指向同一 13306 MySQL；若 kuanilens/database 的 catalog 指向别处，目标装载与比较将不一致。属于外部环境依赖。
- 修复建议：脚本头注明"Trino/Presto 的 mysql catalog 必须指向 13306 MySQL"这一前提；或改用 trino-cli/presto-cli 执行装载。

### P2 / 中

#### 7. Doris 端口已参数化，但 YAML 仍硬编码 9031 且脚本默认 9030 不一致

- 文件：`examples/run-comparison-test.sh:61-62`（`DORIS_PORT` 默认 9030）、`examples/mysql-doris/0{1..5}.yaml`（URL 硬编码 9031，共 6 处）
- 问题：脚本端口参数化只影响装载命令；CLI 比较读取 YAML 中的 URL。Doris 实际端口若为 9030（Doris 默认 FE 查询端口），YAML 9031 导致比较失败；若为 9031，脚本默认 9030 导致装载失败。外部 compose 不在仓库内无法判定。修复请求第 8 项"端口参数化并披露"只完成一半（脚本侧），YAML 侧未参数化且两处默认值不一致。
- 修复建议：统一 YAML 与脚本默认端口（确认外部 compose 后二选一），或将 Doris 端口经环境变量注入 YAML；至少在脚本头明确披露"YAML 硬编码 9031、脚本默认 9030，需人工确认"。

#### 8. 脚本 `load_target_data` 对 SQL Server 使用 `-i /dev/stdin`

- 文件：`examples/run-comparison-test.sh:196`
- 问题：`sqlcmd -S ... -i /dev/stdin` 依赖 sqlcmd 支持从 stdin 读取脚本；部分版本/平台行为不一（macOS 常见版本对 `/dev/stdin` 支持不稳定）。属于外部客户端兼容性风险，未在仓库内验证。
- 修复建议：改用进程替换或临时文件；无法验证时在脚本头披露该风险。

#### 9. 脚本缺失凭据 fail-fast 已实现，但无汇总信息

- 文件：`examples/run-comparison-test.sh:77-99`
- 问题：`require_env` 定义后立即调用 22 次，此时 `log_*` 函数已定义，round-1 的"函数未定义"bug 已修复；但缺失凭据时脚本在任何输出前直接 exit 1，无缺失清单汇总。可读性小问题，不阻断。
- 修复建议：可接受；如需更友好可在 `main` 内调用并汇总缺失清单。

### 已验证通过的修复项（repair-request 八项逐项）

1. **shade 配置**：`consilens-cli/pom.xml:160-196` 使用 maven-shade-plugin 3.5.3，`mainClass` 为 `com.consilens.cli.ConsilensCliApplication`，与 `consilens-mcp/pom.xml` 的 transformer 模式一致；额外排除 `META-INF/*.SF/DSA/RSA`（consilens-mcp 无此 filter，但为修复签名冲突所必需，合理增量）。`createDependencyReducedPom=false` 不生成 dependency-reduced-pom，不影响其他构建目标；dist 装配 `assembly.xml` 的 libs/plugins 依赖集按 groupId 收集独立 jar，不受 shade 影响；`bin/consilens-cli.sh` 使用 classpath 启动亦不受影响。产物验证：`target/consilens-cli-0.1-SNAPSHOT.jar`（62MB）MANIFEST `Main-Class` 正确，无 `.SF/.DSA/.RSA` 残留，`java -jar` 打印 usage 正常（exit 0）。
2. **脚本 JAR 路径与装载**：`run-comparison-test.sh:40` 默认 `consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` 与 Maven 产物一致，保留 `CONSILENS_CLI_JAR` 覆盖；构建提示 `./mvnw -pl consilens-cli -am package` 正确。10 个目标均有 `load_target_data` 调用（oracle 为显式人工步骤提示）；`command -v` 客户端存在性判断、`|| log_warn` best-effort 语义一致；`bash -n` 通过；函数定义顺序正确（`log_*` 在 `require_env` 之前）；`check_cli` 在 main 内先执行；最终 `FAILED>0 -> exit 1` 正确。
3. **SQL 引号错位**：`load-oceanbase.sql:103`、`load-presto-target.sql:105` 均为 `5000.0000, 'extra', ...`，已修复。
4. **Presto YAML target 库**：01/02 指向 `consilens_demo_target`（一致），04/05 指向 `mydb_target`（一致），02/04/05 的 `users` 表库名正确；**但 03 的 `fact_orders` 表在 `consilens_demo_target` 库不存在（见 P0-2），该项未完全修复**。
5. **凭据外置**：22 个 env 变量在 50 个 YAML、`examples/configs/{cross-db,same-db}` 两个 oceanbase YAML、`load-oracle.sql`（`__ORACLE_APP_PASSWORD__` 占位符）与脚本 `require_env` 清单一一对应（`rg -o` 集合完全一致）；`rg 'Kuanilens|Admin123|IDENTIFIED BY'` 仅命中占位符行；测试类无明文密码；`load-oracle.sql:11-16` 头部说明 sed 注入方式。
6. **.gitignore**：`examples/mysql-doris/*.bak` 已加入（`.gitignore:125`），`git check-ignore -v examples/mysql-doris/01-custom-sql-checksum.yaml.bak` 命中，6 个 `.bak` 用户文件保留但被忽略。
7. **集成测试启用机制**：`@EnabledIfSystemProperty(named = "consilens.it.enabled", matches = "true")` 为 JUnit 5 原生注解，真实可用。默认 `mvn -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest` 通过（Tests run: 3, Skipped: 1）；`-Dconsilens.it.enabled=true` 时参数化用例执行（52 项，45 个因缺凭据 error，符合 fail-fast 设计）。`EXAMPLES_DIR` 基于 `getProtectionDomain().getCodeSource().getLocation()`（`target/test-classes`）向上三级解析到仓库根 `examples`，不依赖 CWD（验证通过）。
8. **Doris 端口**：脚本 `DORIS_PORT` 已参数化（默认 9030）；YAML 仍硬编码 9031 且与脚本默认不一致（见 P2-7），披露不完整。

### 交叉检查结果

- `git diff --check`：pass。
- 旧路径残留：`build/libs`、`consilens-cli-1.0.0`、`gradlew`、`test.profile=integration` 全仓无残留（`rg` 排除 target 与 md 后无命中）。
- `deploy/mysql-init`：`deploy/docker-compose.yml:25` 挂载 `./mysql-init` 相对 `deploy/` 正确解析到已跟踪的 `deploy/mysql-init/01-seed-data.sql`（round-1 主 Agent 更正确认）。
- `examples/configs` 既有引用：`oceanbase-test` 旧路径全仓无残留；两个重命名 YAML 已跟踪且占位符化；`git ls-files` 确认 `examples/configs/{cross-db,same-db}` 下 9 个既有 YAML 未受影响。
- 既有测试失败归因：`AiConfigCommandTest` 复跑失败（expected 0 but was 1）确认为既有问题（AI 命令模块，本单元未修改）；`OracleSqlQueryGeneratorTest` 属 UNIT-SQL-GENERATION 单元范围，不判本单元 fail。

## 实现者未覆盖的风险

1. 02/03/05 的 YAML/SQL 表级契约（P0-1/2/3）——实现者只核查了库名（`_target`/`mydb_target`）而遗漏表级存在性，是本轮最主要缺口。
2. 端口参数化未贯通到 YAML（P2-7）：脚本 `DORIS_PORT` 默认与 YAML 硬编码不一致。
3. Trino/Presto catalog 与 13306 MySQL 的绑定前提未明确披露（P1-6）。
4. 外部数据库未联调：10 对示例的实际装载/比较均未在真实环境验证（本机无客户端与容器），目标装载命令的方言兼容性（尤其 `sqlcmd -i /dev/stdin`）未验证。
5. `consilens.it.<name>` 系统属性命名大小写契约不一致（P1-5），文档与实现有出入。

## Validation 评估

实现者验证记录与证据基本一致：`bash -n` pass（已复跑）；`test-compile` pass；fat jar 62MB 可 `java -jar` 启动（已复跑）；契约扫描结果可信（已复跑凭据/残留/check-ignore）。但实现者验证遗漏了表级装载契约扫描（只覆盖库名与占位符），导致 4 处必然失败的 YAML/SQL 契约断裂未被发现。

## 是否重新运行测试

yes（低成本定向验证）：

- `bash -n examples/run-comparison-test.sh` — pass
- `git diff --check` — pass
- `java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` — pass（usage，exit 0）
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest` — pass（3 run, 1 skipped）
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest -Dconsilens.it.enabled=true` — 52 run, 45 errors（全部为缺凭据 fail-fast，符合设计）
- `./mvnw -pl consilens-cli test -Dtest=AiConfigCommandTest` — fail（既有问题，非本单元引入）
- 契约扫描：`rg`（YAML 库名/表名/占位符、凭据明文、旧路径残留）、`git check-ignore`、`grep -c daily_order_summary`

## 已尝试的风险场景

1. 无凭据运行集成测试 → 参数化用例跳过/缺凭据 error，fail-fast 生效。
2. fat jar 直接启动 → 成功，无 NoClassDefFoundError/签名异常。
3. 装载库 vs YAML URL 交叉比对（50 YAML x 19 load SQL）→ 发现 P0-1/2/3。
4. 端口参数化贯通检查 → 发现 P2-7 不一致。

## Required Changes

1. （P0）为 presto/trino/sqlserver 02 用例补齐 `daily_order_summary` 目标表装载，或改 target 为 `type: sql` 聚合查询。
2. （P0）修正 presto/trino 03 YAML URL 库名为 `production_target`（与 `fact_orders` 装载库一致）。
3. （P0）修正 oceanbase 05 YAML 表库名（URL 改 `mydb` 或资源名加 `mydb.` 前缀）。
4. （P1）统一 `consilens.it.<name>` 系统属性命名大小写并修正 Javadoc。
5. （P1）统一 Doris 端口默认值（脚本 9030 vs YAML 9031）或参数化贯通 YAML，并在脚本头披露。
6. （P1）脚本头明确 Trino/Presto catalog 必须指向 13306 MySQL 的前提。
7. （P2）`sqlcmd -i /dev/stdin` 兼容性披露或改用进程替换。

## 备注

- 本报告全部证据基于仓库内只读检查；外部数据库（kuanilens/database compose）不在仓库内，Doris 端口、Trino/Presto catalog 无法判定对错，已按披露要求记录。
- 未修改任何源码/测试文件，未 stage/commit/push。
