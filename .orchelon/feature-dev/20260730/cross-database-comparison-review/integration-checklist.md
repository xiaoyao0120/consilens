# 集成检查清单（round-1 至 round-4 执行结果）

## SQL 生成到执行器

- [x] 方言方法覆盖与 connector 选择一致：`stringJoin` 扩展点覆盖 Presto/Trino（ARRAY_JOIN）、Oracle（||），其余走 Base CONCAT_WS；`buildDiffColumnsExpression` 下游 `JoinDiffer` 用 split(",")+trim 解析，分隔符变更兼容。
- [x] 差异列输出别名和结果读取逻辑一致：Presto/Trino ARRAY_JOIN 跳过 NULL 与 CONCAT_WS 一致。
- [x] 数值规范化在比较两端产生一致文本：**通过（round-3 修复后）**。Presto `FORMAT('%.Nf')` 固定小数位、Doris 进位传播且无 DOUBLE 中间转换、ClickHouse NULL 整数 `COALESCE(toString(..),'0')`；定向断言与受影响模块 184 测试通过。
- [x] TableSegment 边界可被执行器正确消费：**通过（round-3 修复后）**。sqlserver/mssql 输出带引号字符串，其余方言保留 ANSI 字面量；TableSegmentTest 17/17。

## 配置到 CLI

- [x] YAML 字段与配置模型一致：50 个 YAML 的占位符与脚本 22 个导出变量一一对应；字段与 `@JsonProperty` 映射核对一致（UNIT-CROSS-DB-EXAMPLES Review）。
- [x] connector type、URL、schema 和表名一致：**通过（round-4 修复后）**。Presto/Trino 01/03 target 库、oceanbase 05 `mydb.` 前缀、pg 装载库与查询库（建库+切库）全部对齐；50 YAML × 19 load SQL 表级/库级契约扫描 FAILURES=0（无豁免）。
- [x] CLI 命令、JAR 路径与 Maven 构建产物一致：**通过（round-2 修复后）**。shade fat jar 可 `java -jar` 启动，脚本路径 `consilens-cli/target/consilens-cli-0.1-SNAPSHOT.jar` 与产物一致。
- [x] 测试命名与 Surefire/Failsafe 发现规则一致：**通过（round-2 修复后）**。`@EnabledIfSystemProperty(named="consilens.it.enabled", matches="true")` 真实可用，默认 3 run 1 skipped；误导的 profile 注释已删除。

## 示例数据

- [x] 源端和目标端关键字段可映射：YAML keys/fields 与 load SQL 表结构一致（抽样核对）。
- [x] SQL 方言与目标数据库一致：**通过（round-2 修复后）**。两处引号错位已修复；presto/trino/sqlserver 02 聚合表按各自方言建表装载（round-3）。
- [x] 重命名后的旧路径无有效引用：`oceanbase-test` 无残留；主 Agent 复核确认 `deploy/docker-compose.yml` 位于 `deploy/`，其 `./mysql-init` 正确解析到已存在的 `deploy/mysql-init/`，原 round-1 该项发现已撤回。
- [x] 默认凭据仅用于明确的本地示例，且风险已披露：**通过（round-2 修复后）**。脚本与测试类凭据全部外置（env/系统属性），YAML 用 `${env.*}` 占位符，load-oracle.sql 用 `__ORACLE_APP_PASSWORD__`；仓库内无本次引入的明文凭据（两个 OceanBase 测试与 Docker ITest 的明文密码为基线 4bbbd1f 既有代码，非本次引入，已披露）。

## 横切检查

- [x] 配置变更已说明：ClickHouse JDBC 0.4.6 -> 0.9.8 本地仓库可解析、编译通过。
- [x] 需要兼容时，旧调用方仍可用：**通过（round-3 修复后）**。Oracle checksum 聚合去尾 `|` 与 MySQL 逐字节一致、空表 COALESCE；Oracle diff_columns 部分列差异非 NULL；Doris 无 DOUBLE 精度损失。
- [x] 测试覆盖关键契约变化：**通过（round-3 修复后）**。5 个既有断言已同步，新增进位/NULL/尾零/部分列差异定向测试；受影响 5 模块 184 测试、其余 4 模块 169 测试、TableSegmentTest 17/17 全部通过。
- [x] 相关模块构建和定向测试结果已记录：受影响模块 test 全部通过；CLI test-compile 与集成测试默认模式通过；consilens-core 全量 31 个 Mockito 环境失败与 2 个 OceanBase 外部库不可达为环境性失败（已披露）。
- [x] Oracle `DBMS_CRYPTO` 授权：javadoc 已补充 `GRANT EXECUTE ON sys.dbms_crypto TO <user>;`（round-3）。
- [x] pg 装载库契约：`load-postgresql.sql` 头部 `\gexec` 条件建库 + `\connect consilens_demo`，与五个 pg YAML 查询库一致（round-4，无豁免扫描 FAILURES=0）。
