# UNIT-SQL-GENERATION 第 3 轮 Review

- Verdict: **pass**
- Confidence: High
- Risk Level: Medium
- 风险等级理由：round-2 四项 Required Changes 均已真正修复，Oracle 聚合文本与 MySQL 逐字节对照成立、Oracle diff_columns 与 MySQL CONCAT_WS 语义一致、Doris 已去除 DOUBLE 中间精度损失；剩余风险集中在 Oracle `EXTRACT/RTRIM/DBMS_CRYPTO` 与 Doris 大 DECIMAL 边界未实机验证（外部数据库不可达），以及 Oracle 空表 checksum 与 MySQL `''` 的字面偏差（运行时由执行器空段短路保护，非阻断）。
- Reviewer：Banach（独立复审，round-3）

## 已阅读的必读文件

- `SKILL.md`（medium-dev-loop，round-2 已完整阅读）
- 对话提供的 `AGENTS.md`（十二条铁律；仓库内未发现额外 AGENTS.md）
- `task-brief.md`
- `implementation-plan.md`
- `work-breakdown.md`
- `run-state.md`
- `units/UNIT-SQL-GENERATION/scope.md`
- `units/UNIT-SQL-GENERATION/reviews/round-2/review.md`（本人 round-2 fail 报告）
- `units/UNIT-SQL-GENERATION/iterations/round-3/repair-request.md`
- `units/UNIT-SQL-GENERATION/implementer/change-summary.md`
- `units/UNIT-SQL-GENERATION/implementer/validation.md`
- `units/UNIT-SQL-GENERATION/implementer/resume-state.md`
- `units/UNIT-SQL-GENERATION/implementer/worklog.md`

## 按需阅读的文件（调用链与基准）

- `MySQLSqlQueryGenerator.java`（`GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|')` + `COALESCE(MD5(...), '')` 基准）
- `BaseSqlQueryGenerator.java`（Base `buildDiffColumnsExpression`、`columnRef`、`buildNullSafeNotEquals`）
- `JoinDiffer.parseDiffColumns`（下游解析）
- `OracleDataTypeHandler.java`（normalize 覆盖，确认与 checksum 拼接无冲突）
- `DorisDataTypeHandler.java`（完整 normalizeDecimal/normalizeFloat）
- `ClickHouseSqlQueryGenerator.java`、`PrestoDataTypeHandler.java`、`TableSegment.java`（round-2 已确认修复项，核对未回退）
- `ChecksumDiffer.java`（空段短路逻辑，评估 Oracle 空表语义影响面）
- `SQLServerDatabaseDialectProvider.java`（connectorType，round-2 已确认）

## 已检查的主要修改（100%）

1. `OracleSqlQueryGenerator.getChecksumSQLWithConcat`（去尾 `|` + 空表 COALESCE + javadoc 授权语句）
2. `OracleSqlQueryGenerator.buildDiffColumnsExpression`（Oracle 独立 override，LTRIM 方案）+ `stringJoin` 注释
3. `OracleSqlQueryGeneratorTest`（聚合断言同步 + 新增部分列差异 diff 明细用例）
4. `DorisDataTypeHandler.normalizeDecimal`（precision>0 与 precision=0 分支去 DOUBLE）+ `DorisDataTypeHandlerTest` 断言
5. `OceanBaseSameDbComparisonTest`（注释同步）

## 是否重新运行测试：yes

- `./mvnw -q -pl oracle,clickhouse,doris,presto,trino -am test` — pass（surefire 汇总 184 个，fail=0，其中 Oracle 35）
- `./mvnw -q -pl consilens-core -am test -Dtest=TableSegmentTest -Dsurefire.failIfNoSpecifiedTests=false` — pass（17/17）
- `git diff 4bbbd1f --check` — pass
- 与实现者 validation.md 记录的 184/184、TableSegmentTest 17/17、`git diff --check` 一致。

## 逐项发现（按严重度排序）

无阻断性缺陷。round-3 repair-request 四项全部成立：

### 1. Oracle 聚合文本去尾 `|` + 空表处理 —— 已修复 ✅

- 位置：`consilens-connector/consilens-connector-plugins/consilens-connector-oracle/src/main/java/com/consilens/connector/oracle/OracleSqlQueryGenerator.java:88`
- 现 SQL：`LOWER(RAWTOHEX(DBMS_CRYPTO.HASH(COALESCE(RTRIM(EXTRACT(XMLAGG(XMLELEMENT(E, row_checksum || '|') ORDER BY pk_key), '//text()').GETCLOBVAL(), '|'), ''), 2)))`
- 核对：非空段聚合文本为 `md5hex1|...|md5hexN|`，`RTRIM(..., '|')` 去尾后为 `md5hex1|...|md5hexN`，与 MySQL `GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|')`（`MySQLSqlQueryGenerator.java:96`）逐字节一致 ✅；`ORDER BY pk_key` 语法合法、`EXTRACT + GETCLOBVAL` 组合正确。
- 测试断言已同步：`OracleSqlQueryGeneratorTest` 断言 `COALESCE(RTRIM(`、`GETCLOBVAL(), '|'), '')` 且无 LISTAGG，不再固化带尾 `|` 的错误行为 ✅。

### 2. Oracle diff_columns NULL 传播 —— 已修复 ✅

- 位置：`OracleSqlQueryGenerator.java:197-231`（buildDiffColumnsExpression override）
- 核对：每个变化列贡献 `CASE WHEN ... THEN ', "col"' END`（无 ELSE，未变化列返回 NULL），Oracle `||` 将 NULL 视为空串，因此未变化列不再产生残余分隔符；`LTRIM(concat, ', ')` 去掉首个 `, ` 前缀；`COALESCE(..., '')` 覆盖全空形态；外层 `'[' || ', ' || ... || ', ' || ']'`。
- 输出形态逐场景对照 MySQL `CONCAT_WS(', ', '[', cases..., ']')`（Base 实现）：
  - 全部列不同（3 列）：`[, "name", "amount", "status", ]` — 两端一致 ✅
  - 仅第 2 列不同：`[, "amount", ]` — 两端一致 ✅（未变化列不残留空槽）
  - 全相同：`[]` — 两端一致 ✅（LTRIM(NULL 拼接结果) + COALESCE）
- 单列场景：`'[' || ', ' || LTRIM(', "name"') || ', ' || ']'` = `[, "name", ]`，与 MySQL 一致 ✅。
- 下游 `JoinDiffer.parseDiffColumns`（split(",")+trim+去引号）对 `[, "amount", ]` 解析为 `["amount"]`，兼容 ✅。
- `columnRef` 与 Base 签名一致（`OracleSqlQueryGenerator.java:234` vs `BaseSqlQueryGenerator.java:505`），`buildNullSafeNotEquals` 继承 Base（NULL 安全），无行为偏差。
- 定向测试 `testGetJoinDiffDetailSQLKeepsChangedColumnsWhenOnlySomeDiffer`（`OracleSqlQueryGeneratorTest.java`）断言 `'[' || ', ' || COALESCE(LTRIM(`、每个列的前导 `, "col"'`、无 `ELSE NULL`、无 `CONCAT_WS`，编码了"部分列差异时表达式非 NULL 且不丢列名"的 WHY ✅。

### 3. Doris normalizeDecimal 去除 DOUBLE 中间精度损失 —— 已修复 ✅

- 位置：`consilens-connector/consilens-connector-plugins/consilens-connector-doris/src/main/java/com/consilens/connector/doris/DorisDataTypeHandler.java:243-260`
- 核对：`ROUND(\`amount\`, 4)` / `TRUNCATE(...)` 直接作用于 DECIMAL 列（无 `CAST(... AS DOUBLE)`），随后 `CAST(... AS DECIMAL(38, min(p,30))) AS CHAR` 定标保尾零；precision=0 分支（第 246-248 行）同样无 DOUBLE。进位语义成立（整体 ROUND 后定标，`-1.99999 p=4 → '-2.0000'`）；`(long)Math.pow(10, precision)` 溢出路径已移除。
- 测试断言：`testNormalizeDecimalCarriesRoundingIntoIntegerPart` 断言 `ROUND(\`amount\`, 4)`、`AS DECIMAL(38, 4)`、`'0.0000'`，且 `assertFalse(result.contains("AS DOUBLE"))`——不再固化 DOUBLE 表达式文本 ✅；`testNormalizeDecimalCapsPrecisionToDecimalLimit` 覆盖 p=38 → DECIMAL(38,30) ✅。
- `normalizeFloat` 保留 `CAST(... AS DOUBLE)`：FLOAT 单精度转 DOUBLE 与 MySQL 对齐，按任务说明为既有合理行为，非缺陷 ✅。

### 4. DBMS_CRYPTO 授权语句 —— 已补充 ✅

- 位置：`OracleSqlQueryGenerator.java:63-66` javadoc 含 `GRANT EXECUTE ON sys.dbms_crypto TO &lt;user&gt;;`（HTML 转义正确，注释中 `<user>` 为占位符）。部署指引层面已闭环（示例 SQL 归属 UNIT-CROSS-DB-EXAMPLES，本单元职责内已完成）。

### 5. OceanBaseSameDbComparisonTest 注释 —— 已同步 ✅

- javadoc/注释/输出字符串全部改为 `orders_backup`，`rg orders_target` 在该文件与 CrossDb 测试中均无残留（无匹配）。

## 交叉检查结果

- MySQL/PostgreSQL/OceanBase 生成器相对 4bbbd1f 无改动（`git diff --stat` 无相关文件），比较语义未破坏 ✅。
- ClickHouse NULL 归一化（`COALESCE(toString(...), '0')`）、Presto 固定小数位（`FORMAT('%.Nf', ...)`）、TableSegment 方言适配（sqlserver/mssql 带引号）保持 round-2 状态，本轮 diff 未触碰 ✅。
- Base `buildDiffColumnsExpression` 本轮未改动；Oracle 独立 override 不干扰 MySQL/PG/OceanBase 路径 ✅。
- `git diff 4bbbd1f --check` 通过 ✅。

## 实现者未覆盖的风险（非阻断）

1. **Oracle 空表 checksum 与 MySQL `''` 的字面偏差**：`COALESCE(RTRIM(NULL), '')` 使 `DBMS_CRYPTO.HASH('', 2)` 返回 `MD5('')` 的 hex（`d41d8cd9...`），而 MySQL 空表 `COALESCE(MD5(NULL), '')` 返回 `''`，两者并不相等。repair-request 中"空表为 `''` 与 MySQL 对齐"的描述在字面上未完全达成（COALESCE 实际作用是防止 DBMS_CRYPTO 对 NULL 入参报错）。运行时无影响：`ChecksumDiffer` 对 `count==0 && count==0` 有 `empty_segment` 短路，不比较 checksum（round-2 已核实）。建议后续将 javadoc/注释措辞改为"空表时防止 NULL 入参报错，checksum 相等性由执行器空段短路保证"，或改用 `CASE WHEN COUNT(*)=0 THEN '' ELSE ... END` 完全对齐（可选优化，非本轮阻断）。
2. Oracle `EXTRACT/RTRIM/DBMS_CRYPTO` 与 Doris `DECIMAL(38,30)` 直接 ROUND 未实机验证：外部数据库不可达，已充分披露，作为剩余风险跟踪。
3. Doris 大 DECIMAL 进位到整数部分溢出（边缘场景）：`DECIMAL(38,30)` 列值接近 8 位整数上限时，ROUND 进位到 9 位整数会超出 `DECIMAL(38,30)` 的整数位容量导致 CAST 溢出；需显式配置 p≥30 才触发，默认 p=4 不受影响。建议实机验证时覆盖该边界。

## Validation 评估

实现者提供了完整验证记录（184/184、169/169、TableSegmentTest 17/17、`git diff --check`）。我重新运行了受影响 5 模块测试（184 个全部通过，Oracle 35）与 TableSegmentTest（17 个通过）、`git diff --check`（通过），与记录一致。consilens-core 全量 Mockito 环境失败与 OceanBase 外部库不可达属于环境性失败，与本单元改动无关，接受。测试断言不再固化缺陷行为（Oracle 断言含 RTRIM/COALESCE、Doris 断言含无 AS DOUBLE），与修复意图一致。

## Required Changes

无（本轮无阻断项）。

## 接受的非阻塞缺口及原因

- Oracle 空表 checksum 为 `MD5('')` 而非 `''`：运行时由 `ChecksumDiffer` 空段短路保护，无假差异；建议注释澄清（见上）。
- Oracle/Doris 新 SQL 表达式未实机验证：外部库不可达，已在 validation.md 与 change-summary.md 披露，作为集成阶段实机对照项。
- `stringJoin` 保留原 `||` 实现：已确认除 diff_columns（现由 Oracle 独立 override 处理）外无其他调用方，注释已说明 NULL 语义，保持。
- `OceanBase*ComparisonTest` 断言弱化为 `totalDifferences > 0`：round-1/round-2 已接受（bisection 数量波动），保持。
