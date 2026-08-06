# UNIT-SQL-GENERATION 验证结果

## 已运行命令

- `mvn -pl <oracle,clickhouse,doris,presto,trino> -am test` — pass（Oracle 35、ClickHouse 38、Doris 37、Trino 37、Presto 37，共 184 个测试全部通过）
- `mvn -pl <mysql,postgresql,oceanbase,base> -am test` — pass（MySQL 49、PostgreSQL 45、OceanBase 65、Base 10，共 169 个测试全部通过；确认未破坏既有比较语义）
- `mvn -pl consilens-core -am test -Dtest=TableSegmentTest -Dsurefire.failIfNoSpecifiedTests=false` — pass（17/17，确认 round-2 方言适配保持成立）
- `git diff --check` — pass

## 已验证行为

- Oracle checksum SQL 包含 `COALESCE(RTRIM(EXTRACT(XMLAGG(XMLELEMENT(E, row_checksum || '|') ORDER BY pk_key), '//text()').GETCLOBVAL(), '|'), '')`：聚合文本无尾部 `|`、空表为 `''`，与 MySQL `GROUP_CONCAT(..., SEPARATOR '|')` 对齐；测试断言含 RTRIM/COALESCE 且无 LISTAGG。
- Oracle diff 明细：`getJoinDiffDetailSQL` 生成 `'[' || ', ' || COALESCE(LTRIM(CASE WHEN ... THEN ', "name"' END || ...), '') || ', ' || ']'`，部分列差异时表达式非 NULL，变化列名保留；定向测试断言无 `ELSE NULL`。
- Doris `normalizeDecimal`：`ROUND(\`amount\`, 4)` 直接作用于 DECIMAL 列，`AS DECIMAL(38, 4)` 定标，无 `AS DOUBLE`；precision=0 分支同样无 DOUBLE；进位语义与 p=38 截断（DECIMAL(38,30)）断言保留。
- Oracle javadoc 含 `GRANT EXECUTE ON sys.dbms_crypto TO <user>;` 授权语句。
- OceanBaseSameDbComparisonTest 注释统一为 orders_backup。
- ClickHouse NULL 归一化、Presto 固定小数位、TableSegment 方言适配保持 round-2 通过状态（相关模块测试重跑通过）。

## 失败检查

- 无（受控范围内）。
- 已知环境性失败（与本单元改动无关，round-2 已披露）：consilens-core 全量 31 个 Mockito 测试因 MockMaker 初始化失败；2 个 OceanBase 测试因外部数据库不可达。

## 未运行检查

- 检查项：Oracle `EXTRACT/RTRIM/DBMS_CRYPTO`、Doris `DECIMAL(38,30)` 直接 ROUND 在真实数据库上的执行与跨库 checksum 对照。
- 原因：无外部数据库环境。
- 风险：以上 SQL 表达式为语法/语义推断；建议在有环境时执行 MySQL↔Oracle、MySQL↔Doris 的 checksum 与 diff 明细对照。

## 手工验证

- 生成 SQL 文本抽查：Oracle 聚合含 `COALESCE(RTRIM(...), '')`；Oracle diff 含 `LTRIM(CASE WHEN ... THEN ', "col"' END ...)`；Doris 无 `AS DOUBLE`。

## 建议的额外验证

- 真实数据库联调 MySQL↔Oracle checksum（含空表、非空表）与 diff_columns 部分列差异明细。
- 有环境时运行 `mvn -pl consilens-core -am test` 全量（需修复 Mockito MockMaker 环境）。
