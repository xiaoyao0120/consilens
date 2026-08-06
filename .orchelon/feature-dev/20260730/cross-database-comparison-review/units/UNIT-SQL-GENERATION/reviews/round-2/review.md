# UNIT-SQL-GENERATION 第 2 轮 Review

- Verdict: **fail**
- Confidence: High
- Risk Level: High
- 风险等级理由：Oracle 聚合文本仍与 MySQL 不一致（尾部 `|`），MySQL↔Oracle 跨库 checksum 系统性不匹配；Doris 引入 DOUBLE 中间精度损失；Oracle diff 明细列存在 NULL 传播；涉及 SQL 方言与跨库数据正确性。
- Reviewer：Banach（独立复审，round-2）

## 已阅读的必读文件

- `SKILL.md`（medium-dev-loop）
- 对话提供的 `AGENTS.md`（十二条铁律；仓库内未发现额外 AGENTS.md）
- `task-brief.md`
- `implementation-plan.md`
- `work-breakdown.md`
- `run-state.md`
- `units/UNIT-SQL-GENERATION/scope.md`
- `units/UNIT-SQL-GENERATION/reviews/round-1/review.md`
- `units/UNIT-SQL-GENERATION/iterations/round-2/repair-request.md`
- `units/UNIT-SQL-GENERATION/implementer/change-summary.md`
- `units/UNIT-SQL-GENERATION/implementer/validation.md`
- `units/UNIT-SQL-GENERATION/implementer/worklog.md`
- `units/UNIT-SQL-GENERATION/implementer/resume-state.md`
- `integration-checklist.md`

## 按需阅读的文件（调用链与基准）

- `MySQLSqlQueryGenerator.java` / `MySQLDataTypeHandler.java`（checksum 与数值/时间归一化基准）
- `BaseSqlQueryGenerator.java`（stringJoin 扩展点、buildDiffColumnsExpression、getJoinDiffDetailSQL）
- `BaseDataTypeHandler.java`（normalizeColumn 分发、时间格式解析）
- `ClickHouseSqlQueryGenerator.java` / `ClickHouseDataTypeHandler.java`（完整实现）
- `DorisSqlQueryGenerator.java` / `DorisDataTypeHandler.java`（完整实现）
- `OracleDataTypeHandler.java`（normalize 覆盖）
- `TrinoSqlQueryGenerator.java` / `TrinoDataTypeHandler.java`、`PrestoSqlQueryGenerator.java`（完整实现）
- `RelationalCompareSegmentAdapter.java`、`AbstractDatabaseAdapter.java`、`DefaultDatabaseAdapter.java`（TableSegment.database 注入链、getConnectorType 来源）
- `ChecksumDiffer.java`（空段/checksum 相等短路逻辑）
- `JoinDiffer.java`（parseDiffColumns 下游解析）
- `SQLServerDatabaseDialectProvider.java`（connectorType = "sqlserver"）

## 已检查的主要修改（100%）

1. `OracleSqlQueryGenerator.getChecksumSQLWithConcat` + `stringJoin`
2. `DorisDataTypeHandler.normalizeDecimal` + `DorisSqlQueryGenerator.getChecksumSQL`
3. `ClickHouseSqlQueryGenerator`（checksum/row_hash/pk_key）+ `ClickHouseDataTypeHandler`（normalize 原列 + formatForChecksum 系列）
4. `PrestoDataTypeHandler.normalizeDecimal/normalizeFloat`
5. `TableSegment.formatValue/formatTemporalLiteral/isSqlServerConnector`
6. 测试：Oracle/ClickHouse(DTH+SQG)/Trino(DTH)/Presto(DTH)/Doris(DTH)/TableSegmentTest 断言同步与新增
7. 两个 `OceanBase*ComparisonTest.java`

## 是否重新运行测试：yes

- `./mvnw -q -pl oracle,clickhouse,doris,presto,trino -am test` — pass（surefire 汇总 183 个，fail=0）
- `./mvnw -q -pl consilens-core -am test -Dtest=TableSegmentTest -Dsurefire.failIfNoSpecifiedTests=false` — pass（17/17）
- `git diff 4bbbd1f --check` — pass
- 与实现者 validation.md 记录的 183/183、TableSegmentTest 17/17、`git diff --check` 一致。

## 逐项发现（按严重度排序）

### P1-1（阻断）Oracle 聚合文本带尾部 `|`，与 MySQL GROUP_CONCAT 语义仍不一致 —— repair-request 第 1 项未真正修复

- 位置：`consilens-connector/consilens-connector-plugins/consilens-connector-oracle/src/main/java/com/consilens/connector/oracle/OracleSqlQueryGenerator.java:85`
- 现 SQL：`LOWER(RAWTOHEX(DBMS_CRYPTO.HASH(EXTRACT(XMLAGG(XMLELEMENT(E, row_checksum || '|') ORDER BY pk_key), '//text()').GETCLOBVAL(), 2)))`
- 问题：每行的 XML 文本节点内容为 `row_checksum || '|'`，`EXTRACT(..., '//text()')` 把所有文本节点拼接后形如 `md5hex1|md5hex2|...|md5hexN|`，**末尾多一个 `|`**。MySQL 基准（`MySQLSqlQueryGenerator.java` 的 `GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|')`）输出 `md5hex1|...|md5hexN`，无尾部管道符。两端 hash 输入不同，任何非空段的聚合 checksum 必然不匹配，MySQL↔Oracle 比较会产生系统性假差异——这正是 round-1 P1-1 想消除的问题，只是从"含 XML 标签"换成了"尾部多一个分隔符"，未与 MySQL 对齐。
- ORDER BY 语法本身合法（Oracle XMLAGG 支持 `ORDER BY` 子句），`EXTRACT + GETCLOBVAL` 去标签方向正确，但去标签后的文本仍不匹配 MySQL。
- 修复建议：改为 `RTRIM(EXTRACT(XMLAGG(XMLELEMENT(E, row_checksum || '|') ORDER BY pk_key), '//text()').GETCLOBVAL(), '|')`，或在 XMLELEMENT 内不拼 `'|'`、聚合后在纯文本上以其他方式补分隔符；必须保证与 MySQL `GROUP_CONCAT(..., SEPARATOR '|')` 逐字节一致（空集除外，见下）。
- 附带问题：空表时 Oracle `XMLAGG` 为 NULL → checksum 为 NULL，而 MySQL 空表 `COALESCE(MD5(NULL),'')` 为 `''`。当前 `ChecksumDiffer` 对 `count==0 && count==0` 有短路（`ChecksumDiffer.java` 中 `empty_segment` 分支），不产生假差异，但依赖执行器保护；若后续比较路径直接对比 checksum，空表语义不一致。建议顺带把 Oracle 侧 `COALESCE(..., '')` 对齐。

### P1-2（阻断级风险，建议阻塞修复）Oracle `stringJoin` 的 `||` 遇 NULL 传播，diff_columns 明细在部分列差异时整体为 NULL

- 位置：`OracleSqlQueryGenerator.java:179-190`（stringJoin override）+ `BaseSqlQueryGenerator.java:561-582`（buildDiffColumnsExpression）
- 问题：Base `buildDiffColumnsExpression` 现在用 `stringJoin("', '", ['[', CASE..., ']'])`。MySQL 端 `CONCAT_WS` 跳过 NULL 元素；Oracle override 用 `'[' || ', ' || CASE WHEN ... THEN '"col"' ELSE NULL END || ', ' || ']'`。Oracle 的 `||` 遇 NULL 整体为 NULL：只要有一列无差异（CASE 返回 NULL），整个 diff_columns 表达式为 NULL，下游 `JoinDiffer.parseDiffColumns` 只能得到空列表，**实际变化的列名丢失**（行仍判 mismatch，不产生假差异行，但明细列信息缺失）。全部列都不同时才侥幸与 MySQL 输出同形。
- 这是 round-1 已列为"已尝试的风险场景"（Oracle stringJoin 的 NULL 传播）但未进 Required Changes、round-2 未修复的问题。
- 修复建议：Oracle stringJoin 中把每个 arg 包 `NVL(arg, NULL)` 无效（仍为 NULL 元素）；应把 CASE 的 ELSE 改为 `''`（空串）并用 `LISTAGG`/`XMLAGG` 过滤空串，或在 Oracle 侧重写 `buildDiffColumnsExpression` 为 `'[' || LISTAGG(...) WITHIN GROUP... || ']'` 形式，保证与 MySQL `CONCAT_WS` 跳过 NULL 的语义一致。

### P2-1 Doris `normalizeDecimal` 引入 DOUBLE 中间精度损失（round-2 新引入）

- 位置：`consilens-connector/consilens-connector-plugins/consilens-connector-doris/src/main/java/com/consilens/connector/doris/DorisDataTypeHandler.java:256-258`
- 现实现：`TRIM(CAST(CAST(ROUND(CAST(col AS DOUBLE), p) AS DECIMAL(38, min(p,30))) AS CHAR))`。
- 已确认：进位传播修复正确（整体 ROUND 后定标，`-1.99999 p=4 → '-2.0000'`）；`(long) Math.pow(10, precision)` 溢出路径已移除（代码中无 Math.pow，`p=38 → DECIMAL(38,30)` 上限处理存在）；precision=0 分支同步改为 DOUBLE 转换。
- 新风险：`CAST(col AS DOUBLE)` 把 DECIMAL 转二进制浮点，53 位尾数约 15-17 位有效数字；对 DECIMAL(38,30) 或大整数金额（如 `12345678901234567890.1234`）会丢精度，输出与 MySQL `FORMAT(ROUND(col, p), p)`（直接对 DECIMAL 运算）不一致，产生假差异。Doris `ROUND/TRUNCATE` 本身支持 DECIMAL 输入，DOUBLE 转换不是必需的。
- 修复建议：去掉 `CAST(... AS DOUBLE)`，直接 `ROUND(col, p)`/`TRUNCATE(col, p)` 后 `CAST(... AS DECIMAL(38, min(p,30))) AS CHAR`。
- 测试问题：`DorisDataTypeHandlerTest.testNormalizeDecimalCarriesRoundingIntoIntegerPart` 只断言 SQL 文本包含 `CAST(ROUND(CAST(... AS DOUBLE), 4) AS DECIMAL(38, 4))`，把有缺陷的 DOUBLE 表达式固化为预期，且没有真正验证进位语义（铁律 9：测试应编码 WHY）。建议改为断言 `ROUND(` 直接作用于列引用、且不含 `AS DOUBLE`。

### P2-2 Presto 数值规范化修复正确，但存在默认精度与 FLOAT 类型差异的未覆盖风险

- 位置：`consilens-connector/consilens-connector-plugins/consilens-connector-presto/src/main/java/com/consilens/connector/presto/PrestoDataTypeHandler.java:204-255`
- 已确认：`FORMAT('%.Nf', ROUND(...))` 恢复固定小数位，尾零保留，与 MySQL `FORMAT(ROUND(col,N),N)` 对齐；`normalizeFloat` 先 `CAST AS DOUBLE` 与 MySQL 一致；DOUBLE 也走 `normalizeDecimal`，覆盖完整。round-1 P1-4 修复成立。
- 未覆盖风险：`FORMAT` 的舍入是二进制浮点舍入，`ROUND` 与 MySQL `FORMAT` 在半数边界上的行为需在真实 Presto 上对照；实现者已披露未实机验证，接受为剩余风险（非阻断）。

### P2-3 ClickHouse NULL 归一化修复正确；时间列格式分支核对无错

- 位置：`consilens-connector/consilens-connector-plugins/consilens-connector-clickhouse/src/main/java/com/consilens/connector/clickhouse/ClickHouseSqlQueryGenerator.java:70-103（checksum）、131-155（row_hash）`
- 已确认：checksum 与 row_hash 的每个列都包 `COALESCE(toString(formatForChecksum(...)), '0')`，NULL 整数归一化为 `'0'`，与 MySQL `COALESCE(..., '0')` 对齐；concat 嵌套括号正确（`lower(hex(MD5(concat(x, '|', y))))`）；`formatForChecksum` 的 date/time/datetime/timestamp 分支判断正确（`typeName.contains("date") && !contains("time")` 先于 time 分支，`datetime`/`timestamp` 正确落入 `formatDateTimeForChecksum`）。round-1 P1-3 修复成立。
- 次要：pk_key 单列分支 `COALESCE(toString(COALESCE(toString(col), '')), '0')` 存在双层 COALESCE/toString 冗余（`ClickHouseSqlQueryGenerator.java:74`），无害；`ORDER BY pk_key LIMIT 10000000` 为基线已有行为（4bbbd1f 即存在），非本次引入。
- 未覆盖风险：datetime 分支不做时区转换而 MySQL `normalizeDateTime` 用 `CONVERT_TZ(..., '+00:00')`，两端时区假设一致时对齐；若 session 时区不同会产生差异，建议在文档中披露。

### P2-4 TableSegment 方言适配正确，但 date-like 字符串判定对 VARCHAR 键有隐式语义变化

- 位置：`consilens-core/src/main/java/com/consilens/core/segment/TableSegment.java:649-682`
- 已确认：sqlserver/mssql 输出带引号字符串（`formatTemporalLiteral` 中 `isSqlServerConnector("sqlserver"/"mssql")`），其余方言保留 ANSI 字面量；方言依据 `database.getConnectorType()`，SQL Server 实际注册值为 `"sqlserver"`（`SQLServerDatabaseDialectProvider.getConnectorType()`），覆盖正确；`LocalDateTime 'T'` 统一替换为空格，对 MySQL/PG/Oracle 无破坏（这些方言接受 `TIMESTAMP '... ...'` 字面量）。round-1 P1-5 修复成立；`TableSegmentTest` 新增 SQL Server 带引号与 ANSI 字面量两个定向用例（JDK Proxy 模拟 adapter），断言合理。
- 未覆盖风险（round-1 引入、round-2 未处理）：`formatValue` 对"长得像日期/时间戳的字符串"（`strVal.matches("\\d{4}-\\d{2}-\\d{2}")` 等，第 654-657 行）无差别输出 `DATE '...'`/`TIMESTAMP '...'` 字面量。若分段键是 VARCHAR 但存日期字符串，MySQL/PostgreSQL 会把列隐式转日期再比较，从字符串比较变为日期比较；非日期字符串会报错或产生 NULL。分段键通常为主键（数值/日期），该场景概率低，但建议按列类型而非值内容决定字面量形式。

## P3 次要问题

- `OceanBaseSameDbComparisonTest.java:22,26-27,56,96`：javadoc/注释/输出仍写 "orders_target"（`orders vs orders_target`、`// Target: OceanBase orders_target`），而实际 target 资源已是 `mydb.orders_backup`（第 65 行）。表名统一修复正确（两个测试 target 均为 `orders_backup`），但注释陈旧，建议同步。
- `OceanBase*ComparisonTest` 断言弱化为 `assertTrue(totalDifferences > 0)`：round-1 已接受（bisection 导致精确数量波动），保留。
- Oracle 测试断言 `EXTRACT(XMLAGG(XMLELEMENT(E, row_checksum || '|') ORDER BY pk_key), '//text()')`（`OracleSqlQueryGeneratorTest.java`）固化了带尾部 `|` 的错误行为（见 P1-1），修复后需同步改断言。
- Oracle `DBMS_CRYPTO` EXECUTE 权限（round-1 P2-3）：注释声称"granted to consilens user"，但授权语句仍未在本单元或示例中提供，部署到新环境会运行失败；属于 UNIT-CROSS-DB-EXAMPLES 或文档职责，未闭环。

## 交叉检查结果

- MySQL/PostgreSQL/OceanBase 生成器相对 4bbbd1f 无改动（`git diff --stat` 无相关文件），比较语义未被破坏。✅
- Base `buildDiffColumnsExpression` round-2 未再改动；`JoinDiffer.parseDiffColumns`（split(",")+trim）兼容 `', '` 分隔；MySQL/PostgreSQL/OceanBase 走 Base `CONCAT_WS` 正常。✅
- `git diff 4bbbd1f --check` 通过。✅
- 两个 OceanBase 测试 target 统一为 `orders_backup`（seed 实际建表），CrossDb/SameDb 均一致。✅

## 实现者未覆盖的风险

1. **Oracle 聚合文本尾部 `|` 导致 MySQL↔Oracle 非空段 checksum 系统性不匹配（阻断）**。
2. Oracle `stringJoin`/`||` 的 NULL 传播导致 diff_columns 明细列在部分列差异时整体为 NULL（信息丢失，round-1 已知未修）。
3. Doris DOUBLE 中间精度损失（round-2 新引入），且定向测试只断言 SQL 文本、固化 DOUBLE 表达式，未验证进位语义。
4. Oracle 空表 checksum 为 NULL vs MySQL `''`，依赖 `ChecksumDiffer` 空段短路保护。
5. TableSegment 对 date-like 字符串无差别输出 ANSI 字面量，VARCHAR 日期键在 MySQL/PG 上比较语义改变。
6. Oracle `DBMS_CRYPTO` 权限授权语句缺失；Presto `FORMAT`/Doris `DECIMAL(38,30)`/Oracle `EXTRACT` 均未实机验证（外部库不可达，已披露）。

## Validation 评估

实现者提供了完整验证记录（183/183、169/169、TableSegmentTest 17/17、`git diff --check`）。我重新运行了受影响 5 模块测试（183 个全部通过）与 TableSegmentTest（17 个通过）、`git diff --check`（通过），与记录一致。consilens-core 全量 31 个 Mockito 环境失败与 2 个 OceanBase 外部库不可达属于环境性失败，与本单元改动无关的判断可接受。测试证据不能覆盖 P1-1/P1-2，因为 Oracle 断言恰好固化了缺陷行为，且没有 MySQL↔Oracle 实机对照。

## Required Changes

1. **Oracle `getChecksumSQLWithConcat`**：聚合文本必须与 MySQL `GROUP_CONCAT(..., SEPARATOR '|')` 逐字节一致——去掉尾部 `|`（如 `RTRIM(..., '|')`），并同步 `OracleSqlQueryGeneratorTest` 断言；顺带将空表 checksum 与 MySQL `''` 对齐。
2. **Oracle diff 明细**：修复 `stringJoin` 的 NULL 传播（或重写 Oracle `buildDiffColumnsExpression`），保证部分列差异时 diff_columns 不为 NULL。
3. **Doris `normalizeDecimal`**：去掉 `CAST(... AS DOUBLE)`，直接对 DECIMAL 列 ROUND/TRUNCATE 后定标；同步修正 `DorisDataTypeHandlerTest`，断言进位语义（而非固化 DOUBLE 表达式文本）。
4. 补充 Oracle `DBMS_CRYPTO` 授权语句或明确移除该依赖方案。

## 接受的非阻塞缺口及原因

- ClickHouse pk_key 双层 COALESCE/toString 冗余：无害，保持。
- `OceanBase*ComparisonTest` 弱化断言：round-1 已接受，bisection 数量波动。
- Presto/Doris/Oracle 新 SQL 表达式未实机验证：外部库不可达，已充分披露，作为剩余风险跟踪。
- OceanBase 测试注释陈旧（orders_target）：仅注释，不影响执行，建议顺手修正。
