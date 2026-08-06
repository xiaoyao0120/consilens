# UNIT-CROSS-DB-EXAMPLES 验证结果

## 已运行命令

- 无豁免逐 YAML 库级契约扫描（round-4）— pass（FAILURES=0，覆盖全部 50 个 YAML，pg 不再豁免）
- 表级契约扫描（round-4）— pass（FAILURES=0）
- `bash -n examples/run-comparison-test.sh` — pass（round-4）
- `./mvnw -q -pl consilens-cli -am test-compile` — pass（round-4）
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest` — pass（round-4，BUILD SUCCESS）
- `git diff --check` — pass（round-4）
- `bash -n examples/run-comparison-test.sh` — pass（round-3）
- 表级契约扫描（round-3，可复现脚本，见文末）— pass（FAILURES=0）
- 库级契约扫描（round-3，可复现脚本，见文末）— pass（FAILURES=0）
- `./mvnw -q -pl consilens-cli -am test-compile` — pass（round-3）
- `./mvnw -pl consilens-cli test -Dtest=CrossDatabaseComparisonIntegrationTest` — pass（round-3，BUILD SUCCESS）
- fat jar `config validate` × 4（presto 02/03、trino 03、oceanbase 05，注入假凭据）— pass（round-3）
- 脚本 dry-run（假凭据，单 pair mysql-presto）— pass（round-3，无数据库时 5 个 YAML 失败 + 汇总 exit 1，符合预期）
- `git diff --check` — pass（round-3）
- 残留扫描（round-3）：`build/libs|consilens-cli-1.0.0|test.profile=integration` 无；`/dev/stdin` 仅剩脚本头披露注释
- 既有 round-2 验证（未回退）：shade fat jar、凭据外置、`.gitignore`、脚本 JAR 路径
- `bash -n examples/run-comparison-test.sh` — pass
- `./mvnw -q -pl consilens-cli -am test-compile` — pass
- `./mvnw -pl consilens-cli test` — fail（137 通过 / 3 失败 / 2 error / 1 skipped；失败均为未修改文件或既有环境问题，见失败检查）
- `./mvnw -pl consilens-cli -am test` — fail（上游 `consilens-connector-oracle` 的 `OracleSqlQueryGeneratorTest.testGetChecksumSQL` 失败，属 UNIT-SQL-GENERATION 单元既有修改）
- `./mvnw -q -pl consilens-cli -am package -DskipTests` — pass（产出约 62MB fat jar）
- `java -jar consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` — pass（正常打印 CLI usage）
- `java -jar ... config validate --config examples/mysql-presto/01-custom-sql-checksum.yaml`（注入假凭据）— pass
- 脚本 dry-run（注入假凭据，单 pair mysql-presto）— pass（装载跳过提示 + 5 个 YAML 因无数据库失败 + 汇总 exit 1，符合预期）
- 脚本缺失凭据 fail-fast — pass（exit 1）
- `git diff --check` — pass
- 契约扫描：`5000\.0000'` 残留无；`Kuanilens|Admin123` 明文残留无；`build/libs|consilens-cli-1.0.0|gradlew|test.profile=integration` 残留无；`git check-ignore examples/mysql-doris/01-custom-sql-checksum.yaml.bak` pass

## 已验证行为

- mysql-pg 装载库契约：`load-postgresql.sql` 头部条件创建 `consilens_demo` 库（`\gexec` 幂等）并 `\connect consilens_demo`，此后 `CREATE TABLE public.*` 落在 `consilens_demo` 库；五个 pg YAML URL（`/consilens_demo?currentSchema=public`）与装载库一致。
- 无豁免库级扫描：pg 逐 YAML 校验 `consilens_demo` 被创建/连接且目标表在 public schema；sqlserver 校验 databaseName ∈ USE/CREATE DATABASE；oracle 校验 SID 出现在装载说明；mysql 协议/presto/trino/clickhouse 校验 URL 库与表限定符；全部 FAILURES=0。
- 表级契约：50 个 YAML 的 target table 资源名（含 `mydb.` 前缀、大表 `fact_orders`、聚合表 `daily_order_summary`）在对应目标装载 SQL 中均存在。
- 库级契约：YAML target URL 库名（mysql/presto/trino/clickhouse/sqlserver/postgresql 方言解析）与装载 SQL 限定符一致；pg 按 public schema、sqlserver 按 USE 库豁免。
- presto/trino/sqlserver 02 聚合表已补齐建表+装载；presto/trino 03 与 pg/tidb 大表模式一致；oceanbase 05 资源名带 `mydb.` 前缀。
- Doris YAML 端口统一 9030（与脚本默认一致）；`consilens.it.<NAME>` 与 Javadoc 一致；脚本头披露 catalog 前提、Doris 端口假设与 sqlcmd 方案。
- fat jar 可 `java -jar` 启动并执行 `config validate`，不再报 `NoClassDefFoundError: slf4j` 或签名冲突。
- 集成测试默认不执行连接用例（未设置 `consilens.it.enabled` 时参数化用例 skip），目录结构用例 2 项通过。
- 测试类凭据仅从系统属性/环境变量读取，源码无明文密码。
- 脚本 JAR 路径与 Maven 产物一致，构建提示正确，端口参数化生效，缺失凭据 fail-fast。
- Presto YAML target 库与 load SQL 装载库一致；两处 SQL 引号错位已修复；`.bak` 已被 `.gitignore` 忽略。

## 失败检查

- `./mvnw -pl consilens-cli test` 的 3 个 Ai 命令测试失败（`AiConfigCommandTest`、`AiDiagnoseCommandTest`、`AiExplainCommandTest`，expected 0 but was 1）：相关文件与依赖均未在本单元修改，判定为既有失败。
- `ExampleConfigurationCompatibilityTest` 2 个 error：`examples/mysql-to-oracle-checksum.yaml` 与 `same-db-oracle-comparison.yaml` 的 `${env.ORACLE_USER}` 在测试的 8 变量 testEnvironment 中缺失；两个 YAML 均为未修改的历史文件，非本单元引入。
- `./mvnw -pl consilens-cli -am test` 的上游 Oracle connector 测试失败：文件属于 UNIT-SQL-GENERATION 单元修改范围。

## 未运行检查

- 检查项：外部数据库真实联调（脚本全量/单 pair 成功）
- 原因：本机无数据库容器与 mysql/psql 等客户端
- 风险：目标装载命令与各 load SQL 的方言兼容性（尤其 pg `\gexec`/`\connect` 元命令、sqlserver/presto/trino 聚合表 DDL）未在真实环境验证

## 手工验证

- 检查 fat jar 启动输出、脚本 dry-run 汇总与退出码、YAML/SQL 契约文本、03/05/02 修复后片段。

## 建议的额外验证

- 有外部数据库环境时运行 `bash examples/run-comparison-test.sh <pair>` 验证装载与比对。
- UNIT-SQL-GENERATION 修复 Oracle connector 后重跑 `./mvnw -pl consilens-cli -am test`。
- 人工确认 kuanilens/database 中 Doris 实际 FE 查询端口（现按 Doris 默认 9030 统一）与 Trino/Presto mysql catalog 指向。
- 有 PostgreSQL 环境时验证 `psql -f examples/mysql-pg/load-postgresql.sql` 的建库/切库幂等性。

## 可复现契约扫描（round-3）

表级扫描（target table 资源名 ↔ 目标 load SQL 建表/装载）：

```bash
for pair_dir in examples/mysql-*; do
  for yaml in "$pair_dir"/0*.yaml; do
    tgt_type=$(awk '/^target:/{f=1;next} f&&/^source:/{f=0} f&&/^[[:space:]]+resource:/{r=1;next} r&&/^[[:space:]]+type:/{sub(/^[[:space:]]*type:[[:space:]]*/,"");print;exit}' "$yaml")
    tgt_name=$(awk '/^target:/{f=1;next} f&&/^source:/{f=0} f&&/^[[:space:]]+resource:/{r=1;next} r&&/^[[:space:]]+name:/{sub(/^[[:space:]]*name:[[:space:]]*/,"");print;exit}' "$yaml")
    if [ "$tgt_type" = "table" ] && [ -n "$tgt_name" ]; then
      tbl="${tgt_name##*.}"
      tgt_sql=$(ls "$pair_dir"/load-*.sql 2>/dev/null | grep -v 'load-mysql.sql' | head -1)
      grep -qiE "\b${tbl}\b" "$tgt_sql" || echo "TABLE-MISSING $yaml -> $tbl"
    fi
  done
done
```

库级扫描（YAML target URL 库名 ↔ 装载 SQL 限定符；pg/sqlserver 按 schema/USE 豁免）结果同为 FAILURES=0，完整脚本与 round-3 输出见本实现者 worklog 对应的命令执行记录。

## round-4 无豁免库级契约扫描（可复现）

扫描规则（对全部 10 组逐 YAML 校验，不再豁免任何组）：

- `mysql-pg`：URL 库名必须为装载 SQL 中 `CREATE DATABASE`/`\connect` 目标（`consilens_demo`），且 target table 以 `public.<tbl>` 存在于装载 SQL。
- `mysql-sqlserver`：URL `databaseName` 必须出现在装载 SQL 的 `USE`/`CREATE DATABASE` 中。
- `mysql-oracle`：URL SID（ORCL）必须出现在装载 SQL 的连接说明中。
- 其余组（mysql 协议/presto/trino/clickhouse）：URL 库名必须被装载 SQL 创建/使用；table 资源显式 `db.tbl` 或 `url_db.tbl` 限定符必须存在。

执行命令（仓库根）：

```bash
bash /tmp/db-contract-scan-r4.sh
```

输出：`----` 后 `FAILURES=0`（无任何 URL-DB-UNPARSEABLE / *_MISSING 行）。
