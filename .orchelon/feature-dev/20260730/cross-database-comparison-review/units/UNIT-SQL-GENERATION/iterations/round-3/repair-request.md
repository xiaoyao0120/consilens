# UNIT-SQL-GENERATION 第 3 轮修复请求

- 上一轮：round-2
- Reviewer 结论：fail（Confidence: High, Risk Level: High）
- 主 Agent 结论：证据充分、已逐条复核，接受 fail 并转为修复请求
- 阻塞问题：
  - Oracle 聚合文本尾部多一个 `|`，与 MySQL `GROUP_CONCAT(..., SEPARATOR '|')` 逐字节不一致，MySQL↔Oracle 非空段 checksum 系统性不匹配（round-2 只从"含 XML 标签"改成"尾部多分隔符"，未真正修复）。
  - Oracle `stringJoin` 用 `||` 拼接，任一列无差异（CASE 返回 NULL）时整个 diff_columns 表达式为 NULL，下游 `JoinDiffer.parseDiffColumns` 丢失变化的列名明细。
  - Doris `normalizeDecimal` 引入 `CAST(col AS DOUBLE)` 中间精度损失，大 DECIMAL 值输出与 MySQL 不一致（round-2 新引入）。
  - Oracle 空表时 checksum 为 NULL 而 MySQL 为 `''`，依赖执行器空段短路保护，建议顺带对齐。
- 必需修改：
  1. `OracleSqlQueryGenerator.getChecksumSQLWithConcat`：聚合文本必须与 MySQL `GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|')` 逐字节一致——去掉尾部 `|`（如 `RTRIM(EXTRACT(XMLAGG(XMLELEMENT(E, row_checksum || '|') ORDER BY pk_key), '//text()').GETCLOBVAL(), '|')`），空表 checksum 用 `COALESCE(..., '')` 与 MySQL 对齐；同步 `OracleSqlQueryGeneratorTest` 断言（当前断言固化了带尾部 `|` 的错误行为）。
  2. Oracle diff 明细：修复 `stringJoin` 的 NULL 传播（如 CASE 的 ELSE 改 `''` 并用过滤空串的聚合，或重写 Oracle `buildDiffColumnsExpression`），保证部分列差异时 diff_columns 不为 NULL；需有部分列差异场景的定向测试。
  3. `DorisDataTypeHandler.normalizeDecimal`：去掉 `CAST(... AS DOUBLE)`，直接对 DECIMAL 列 ROUND/TRUNCATE 后 `CAST(... AS DECIMAL(38, min(p,30))) AS CHAR`（precision=0 分支同步）；修正 `DorisDataTypeHandlerTest`，断言进位语义与不含 `AS DOUBLE`（而非固化 DOUBLE 表达式文本）。
  4. 补充 Oracle `DBMS_CRYPTO` 授权语句或明确移除该依赖方案（本单元注释声称已授权但未提供授权语句）。
- 需要检查的文件：`OracleSqlQueryGenerator.java`、`DorisDataTypeHandler.java`、`BaseSqlQueryGenerator.java`（buildDiffColumnsExpression 调用链）、`JoinDiffer.java`（parseDiffColumns）、对应测试文件。
- 需要新增或重新运行的测试：受影响模块 `mvn test` 必须通过；Oracle 聚合去尾 `|` 与空表 `''` 对齐断言、Oracle 部分列差异 diff_columns 非 NULL 断言、Doris 无 DOUBLE 转换且进位语义断言。
- 需要重复的集成检查：MySQL↔Oracle checksum 拼接逐字节对照；Oracle diff_columns 部分列差异明细；Doris 大 DECIMAL 精度。
- 约束：不 stage、不 commit、不 push；不得改动 UNIT-CROSS-DB-EXAMPLES 拥有的文件。
- 不得破坏：MySQL/PostgreSQL/OceanBase 生成器（相对 4bbbd1f 无改动，保持不动）；ClickHouse NULL 归一化、Presto 固定小数位、TableSegment 方言适配（round-2 已确认修复成立）。
- 下一轮必须提供的证据：`mvn test` 通过结果、Oracle 生成 SQL 样例（无尾 `|`、空表 COALESCE）、Oracle diff_columns 部分列差异样例、Doris 进位断言与 SQL 文本（无 AS DOUBLE）。
