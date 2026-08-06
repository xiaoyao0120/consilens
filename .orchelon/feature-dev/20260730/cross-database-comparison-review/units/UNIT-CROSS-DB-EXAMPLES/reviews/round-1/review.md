# UNIT-CROSS-DB-EXAMPLES Review (Round 1)

## Verdict

**fail**

## Confidence

**high**

## Risk Level

**High**

## 已读文件

### 事实源

- `.orchelon/feature-dev/20260730/cross-database-comparison-review/task-brief.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/implementation-plan.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/work-breakdown.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/scope.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/change-summary.md`
- `.orchelon/feature-dev/20260730/cross-database-comparison-review/units/UNIT-CROSS-DB-EXAMPLES/implementer/validation.md`（实现者未提供验证记录）

### 审查范围内文件

- `consilens-cli/src/test/java/com/consilens/cli/config/CrossDatabaseComparisonIntegrationTest.java`（完整）
- `examples/run-comparison-test.sh`（完整）
- `examples/mysql-pg/`（5 个 YAML + load-mysql.sql + load-postgresql.sql）
- `examples/mysql-doris/`（5 个 YAML + 6 个 `.bak` + load-doris.sql + load-mysql.sql）
- `examples/mysql-oceanbase/`（5 个 YAML + load-oceanbase.sql + load-mysql.sql）
- `examples/mysql-presto/`（load-presto-target.sql、01/03/05 YAML）
- `examples/mysql-trino/`（load-trino-target.sql、01/03 YAML）
- `examples/mysql-clickhouse/`（load-clickhouse.sql 抽样）
- `examples/mysql-oracle/`（load-oracle.sql REC_EXTRA 段）
- `examples/mysql-sqlserver/`、`examples/mysql-tidb/`、`examples/mysql-starrocks/`（REC_EXTRA 段 + load 文件名）
- `deploy/docker-compose.yml`、`deploy/mysql-init/01-seed-data.sql`（git 跟踪确认）
- `examples/configs/cross-db/mysql-to-oceanbase-cross-db.yaml`、`examples/configs/same-db/oceanbase-same-db.yaml`（重命名确认）

### 配置模型 / 关联代码

- `consilens-cli/pom.xml`、根 `pom.xml`（surefire 3.1.2、JUnit 5.10.0、JDK 11、版本 0.1-SNAPSHOT、无 shade/assembly）
- `consilens-cli/src/main/java/com/consilens/cli/ConsilensCliApplication.java`（diff 子命令）
- `consilens-cli/src/main/java/com/consilens/cli/command/DiffCommand.java`（--config）
- `consilens-cli/src/main/java/com/consilens/cli/config/ConfigurationManager.java`、`ConfigNormalizer.java`、`EnvironmentPlaceholderResolver.java`
- `consilens-cli/src/main/java/com/consilens/cli/model/CliConfiguration.java`、`ConnectionConfig.java`、`ComparisonConfig.java`、`StrategyConfig.java`、`CompareMappingConfig.java`、`LocalCompareConfig.java`
- `consilens-cli/src/main/java/com/consilens/cli/model/normalization/NormalizationConfig.java`、`TypeNormalizationRule.java`
- `consilens-cli/src/main/java/com/consilens/cli/service/CompareRequestFactory.java`、`ConnectorConfigMapper.java`
- `consilens-connector-api/.../normalization/DefaultNormalizationSpecValidator.java`
- `consilens-sink-api/.../SinkConfig.java`、`ResultConfig.java`

## 抽样说明

10 组 `mysql-*` 目录中，`mysql-pg` 全量审查（5 个 YAML + 2 个 load SQL）；`mysql-oceanbase` 全量审查（5 个 YAML + 2 个 load SQL）；其余 8 组（clickhouse、tidb、starrocks、oracle、sqlserver、trino、presto、doris）对 YAML 按 01/03/05 三类模板各抽查 1-2 组，对 load SQL 全量检查 REC_EXTRA 插入段与文件存在性。结构一致性结论基于抽样 + 全量文件名/端口/占位符扫描（`rg` 覆盖全部 50 个 YAML 与 19 个 load 文件）。

## Validation 评估

实现者 `validation.md` 明确写着“无可采信的预先验证证据”，即本单元没有任何预先验证。我做了以下独立验证：

- `bash -n examples/run-comparison-test.sh`：通过（脚本语法有效）。
- 全量扫描 50 个 YAML 的 `${env.*}` 占位符：共 22 个变量，与 `run-comparison-test.sh` 导出的 22 个环境变量一一对应。
- YAML 字段与配置模型核对：`source/target.connection.url|username|password`、`resource.type|name|path`、`comparison.keys|fields|mappings|extraColumns|filters`、`strategy.mode|algorithm|localCompare.mode`、`normalization.global|source|target` 及各规则 key、`result.sinks[].format|type|properties`、`result.failOnSinkError` 均与 `@JsonProperty` 字段匹配；`readOptions` 经 `@JsonAnySetter` 吸收。
- 测试发现规则：根 pom surefire 3.1.2，无 `groups/excludedGroups` 或 `skipTests` 配置；测试类以 `@Disabled` 注解禁用，默认测试运行会被跳过（不失败），不会因外部数据库缺失而中断 CI。
- `./mvnw -q -pl consilens-cli -am test-compile`：通过，测试源码可编译。
- 构建产物核对：`consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` 存在（非 fat jar，`java -jar` 报 `NoClassDefFoundError: org/slf4j/LoggerFactory`）。
- 重命名残留扫描：`rg` 全仓未发现 `examples/configs/oceanbase-test` 旧路径引用；`mysql-init` 旧路径仅剩 `deploy/docker-compose.yml:25` 的 `./mysql-init` 挂载（该挂载相对于 `deploy/` 目录解析，重命名后实际指向不存在的 `deploy/mysql-init` 目录——`git ls-files` 确认文件已跟踪为 `deploy/mysql-init/01-seed-data.sql`）。

## 是否重新运行测试

- 运行了 `./mvnw -q -pl consilens-cli -am test-compile`，通过。
- 未运行集成测试（`@Disabled` + 依赖外部数据库 Docker 容器，任务约束不连接外部数据库）。未执行 bash 全量示例运行（本地无 mysql 客户端，且无外部数据库）。
- 未运行完整 `./mvnw test`（跨单元改动涉及 SQL 生成器，属于另一单元范围）。

## 已尝试的风险场景（定向验证）

1. **Maven/JUnit 测试发现**：确认无 surefire 过滤配置；`@Disabled` 生效使默认 `mvn test` 跳过该类（发现但跳过，不失败）。风险点是“集成验证入口永远不会默认执行”，且 `@Disabled` 注释声称用 `-Dtest.profile=integration` 启用，但根 pom 和 cli pom 中均不存在该 profile，启用开关无效——注释与实现不符。
2. **CLI JAR 路径 vs 构建产物**：脚本默认路径 `../consilens-cli/build/libs/consilens-cli-1.0.0-SNAPSHOT.jar` 在 Maven 仓库中不存在；仓库无 `gradlew`、无 `build.gradle`、无 `settings.gradle`，项目是纯 Maven（`mvnw`），版本 `0.1-SNAPSHOT`，产物在 `consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar`。脚本提示 `./gradlew :consilens-cli:shadowJar` 也是 Gradle 命令。即使路径修正，`target/` 下的 jar 是薄 jar（不含依赖），`java -jar` 直接运行报 `NoClassDefFoundError: org/slf4j/LoggerFactory`，脚本会 100% 失败。CLI jar 路径是确定的阻断缺陷。
3. **目标库数据装载**：脚本只实现 `load_mysql_data()`（仅 mysql 客户端，且本机未安装 mysql 客户端），仅对 `load-mysql.sql` 调用；没有为 ClickHouse/PostgreSQL/Oracle/SQL Server/TiDB/StarRocks/Doris/Presto/Trino/OceanBase 中任何一个目标装载 `load-*.sql`。目标数据装载完全缺失，即使 JAR 路径修正，所有对比也会因目标库无数据（或命中历史数据）而失败/产生假结果。
4. **load SQL 语法**：`examples/mysql-oceanbase/load-oceanbase.sql:103` 与 `examples/mysql-presto/load-presto-target.sql:105` 存在引号错位：`5000.0000', 'extra'`（小数点后多一个单引号），MySQL 方言下该 INSERT 语句语法错误，直接破坏 SOURCE_MISSING 用例。其余 8 个 load 文件对应语句正确。确定的 SQL 缺陷。
5. **Doris 端口不一致**：Doris 标准 FE 查询端口是 9030；新版 5 个 YAML 全部使用 9031（StarRocks 也常用 9030）。`.bak` 文件版本用的是 9030。无 docker-compose/kuanilens 配置可核对 Doris 实际端口（`/Users/szh/soft/kuanilens/database` 在仓库外），标注为待确认。
6. **Presto/Trino 目标库不一致**：`load-presto-target.sql`/`load-trino-target.sql` 把数据装进 `*_target` 库，但 `mysql-presto/01`、`03` 的 target URL 指向 `mysql/consilens_demo`（非 `_target`），与 02/04/05 的 `_target` 不一致；Trino 全部指向 `_target`。Presto 的 01/03 会查错库（查不到目标表或命中源表），属高置信缺陷。
7. **Doris 05-same-db-join 表名不一致**：05 的 URL 用 `mydb` 库、resource.name 用 `orders`/`orders_backup`；load-doris.sql 确认创建了 `mydb.orders`/`mydb.orders_backup`，此项一致（`.bak` 版本里 URL 用 `consilens_demo` 反而错，说明 .bak 是旧稿）。
8. **凭据默认值**：脚本与测试类硬编码 10 组数据库默认密码（如 `Kuanilens_MySQL_2026!`、`Kuanilens_Oracle_2026!` 等）。脚本内可被环境变量覆盖，测试类 `testEnvironment()` 是硬编码不可覆盖。凭据以明文形式入库，违反仓库 `AGENTS.md` 的“不提交凭据”安全规则，属于发布前必须处理项。
9. **重命名残留**：`oceanbase-test` 旧路径无残留引用；`deploy/docker-compose.yml` 的 `./mysql-init` 挂载在重命名后指向不存在的目录（相对 deploy/ 解析），MySQL 初始化种子数据将不再加载，属回归。
10. **`.bak` 文件**：`examples/mysql-doris/` 下 6 个 `.bak` 未跟踪且内容与正式 YAML/SQL 存在真实差异（端口 9030 vs 9031、表名、`UNIQUE KEY` vs `DUPLICATE KEY`），不属于可忽略的临时文件，应在提交前清理或按 `.gitignore` 忽略（当前 `.gitignore` 无 `*.bak` 规则）。

## 主要发现

### P0 / 阻断（必须修复后才能视为可交付）

1. **`run-comparison-test.sh` 的 CLI JAR 路径与构建系统不符（示例 100% 不可运行）**
   - 默认 JAR：`${SCRIPT_DIR}/../consilens-cli/build/libs/consilens-cli-1.0.0-SNAPSHOT.jar`，仓库为纯 Maven，版本 `0.1-SNAPSHOT`，产物在 `consilens-cli/target/`。
   - 脚本提示 `cd .. && ./gradlew :consilens-cli:shadowJar`，但仓库无 gradlew/build.gradle/settings.gradle。
   - `consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` 是薄 jar（仅 116 个 class，无依赖），`java -jar` 报 `NoClassDefFoundError: org/slf4j/LoggerFactory`。需要 fat jar（shade/assembly）或修正 classpath 才能运行。
2. **脚本没有装载任何目标库数据**
   - 仅 `load_mysql_data()` 处理 `load-mysql.sql`；10 个目标库的 `load-*.sql`（pg/clickhouse/tidb/starrocks/oracle/sqlserver/trino/presto/oceanbase/doris）从未被调用，脚本注释声称“loads data into source and target databases”与实现不符。
   - 即使修好 JAR，所有对比也会因目标库缺数据失败或产生假结果。
3. **两个 load SQL 语法错误（引号错位）**
   - `examples/mysql-oceanbase/load-oceanbase.sql:103`：`VALUES (... 5000.0000', 'extra', ...)`。
   - `examples/mysql-presto/load-presto-target.sql:105`：同样 `5000.0000', 'extra'`。
   - 其余 8 个目标 load 文件该语句正确（tidb/oracle/sqlserver/doris/starrocks/trino 均 `5000.0000, 'extra'`）。

### P1 / 高

4. **Presto 01/03 的 target 库与装载库不一致**：`mysql-presto/01-custom-sql-checksum.yaml`、`03-large-table.yaml` 指向 `jdbc:presto://127.0.0.1:8085/mysql/consilens_demo`，而 `load-presto-target.sql` 装载 `consilens_demo_target`；同目录 02/04/05 均用 `_target`。Trino 版本全部一致（用 `_target`）。
5. **`docker-compose.yml` 的 MySQL init 挂载在重命名后失效**：`deploy/docker-compose.yml:25` 挂载 `./mysql-init`（相对 deploy/ 解析），重命名后实际目录不存在，`01-seed-data.sql` 不再被 MySQL 容器初始化加载。旧路径 `mysql-init/` 已无任何文件，仓库只跟踪 `deploy/mysql-init/01-seed-data.sql`。
6. **明文凭据入库**：`run-comparison-test.sh` 与 `CrossDatabaseComparisonIntegrationTest.testEnvironment()` 硬编码 22 个默认用户名/密码；测试类无法用环境变量覆盖。违反仓库 AGENTS.md 安全规则（凭据不应提交，应走 `.env.example`/环境变量）。

### P2 / 中

7. **集成测试的启用机制与注释不符**：`@Disabled("... enabled with -Dtest.profile=integration")` 注释声称可用 `-Dtest.profile=integration` 启用，但仓库中无该 profile 定义（根 pom 仅有 4 个与集成测试无关的 profile），该开关无效。若需要可开关的集成测试，应配置 surefire `groups` + JUnit Tags 或 failsafe `IT` 命名。
8. **Doris 端口 9031 待确认**：新版 5 个 YAML 均用 9031；Doris 默认 FE 查询端口是 9030（`.bak` 也是 9030），且 StarRocks 版本用 9030。缺少外部 docker-compose 佐证，无法在仓库内判定对错，需人工确认 `kuanilens/database` 的 Doris 映射。
9. **`.bak` 文件应清理**：`examples/mysql-doris/` 下 6 个 `.bak`（01-05 yaml + load-doris.sql）未跟踪、内容与正式文件有实质差异，不属于构建产物；`.gitignore` 无 `*.bak` 规则。提交前应删除或添加忽略规则，否则会污染示例目录并误导读者。

### 低风险 / 信息

10. `CrossDatabaseComparisonIntegrationTest` 的 `EXAMPLES_DIR = "../examples"` 依赖运行目录为 `consilens-cli/`；在 IDE 默认 working dir（模块根）下成立，但若以仓库根为 CWD 运行会失败。surefire 默认 fork 于模块 basedir，风险低。
11. `load_mysql_data` 的错误被 `|| { log_warn ... }` 吞掉（`set -e` 下仍会继续），若 MySQL 密码错误或表已存在，只会输出 SKIP 提示，数据装载失败不会使脚本失败——与脚本“目标数据必须装载”的语义冲突，建议区分“幂等重跑”与“真实失败”。
12. `run_comparison ... || true` 使单个用例失败不中断，但 `FAILED>0` 时最终 `exit 1`，汇总语义正确。

## Required Changes

按优先级：

1. **修正脚本 JAR 路径**：改为 Maven 产物（如 `consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar`）或支持 `CONSILENS_CLI_JAR` 覆盖的既有设计；同时修正构建提示（`./mvnw -pl consilens-cli -am package` 或提供 shade/assembly 产出可运行 fat jar）。脚本内 Prerequisites 注释同步更新。
2. **为每个目标库装载数据**：在 `main` 循环中按 pair 调用对应目标装载（PostgreSQL `psql`、ClickHouse `clickhouse-client`、Oracle `sqlplus`、SQL Server `sqlcmd`、MySQL 协议系 `mysql`、Presto/Trino 走 `_target` MySQL 库等），或删除“loads data into target”声明并明示目标数据需人工装载。
3. **修复两处 SQL 引号错位**：`examples/mysql-oceanbase/load-oceanbase.sql:103`、`examples/mysql-presto/load-presto-target.sql:105`，改为 `5000.0000, 'extra'`。
4. **统一 Presto 01/03 的 target 库**：与装载库一致改为 `jdbc:presto://127.0.0.1:8085/mysql/consilens_demo_target`（或统一反向调整，需与装载脚本一致）。
5. **修正 `deploy/docker-compose.yml` 挂载**：挂载源改为指向重命名后实际存在的 `mysql-init` 目录（相对 deploy/ 的 `./mysql-init` 当前不存在），并验证 init 脚本被加载。
6. **凭据外置**：脚本保留环境变量默认值可接受时需评估，但测试类 `testEnvironment()` 的硬编码密码应改为读取 `System.getenv`/`System.getProperty`，仓库内不落明文凭据；至少确认这些密码仅用于本地示例环境。
7. **清理或忽略 `.bak` 文件**：删除 `examples/mysql-doris/*.bak` 或加入 `.gitignore`。
8. **集成测试启用机制**：要么按注释实现 `-Dtest.profile=integration` 对应的 surefire profile，要么改用 JUnit Tags + surefire `groups`，要么把类名改为 `*IT` 并接入 failsafe；否则删除误导注释。
9. **人工确认 Doris 9031 端口**（外部 compose 不在仓库内，无法自动判定）。
10. 集成测试 `EXAMPLES_DIR` 改为基于类路径/模块根的稳定定位，避免依赖 CWD。

## 备注

- 本报告基于仓库内证据；Doris 端口、kuanilens/database 容器编排在仓库外，无法验证，已按“待确认”标注而非“阻断”。
- 本单元与 UNIT-SQL-GENERATION 无交叉文件冲突；测试编译验证通过不构成对 SQL 生成器正确性的背书（后者由另一单元负责）。
- 全部证据路径均相对于仓库根 `/Users/szh/soft/consilens`。

## 主 Agent 复核更正（2026-08-02）

- 撤回“`deploy/docker-compose.yml` 的 `./mysql-init` 挂载失效”发现。Compose 文件位于 `deploy/`，相对挂载正确解析为已存在的 `deploy/mysql-init/`；该 staged 重命名实际修复了原根目录错位。此项不进入 round-2 Required Changes。
