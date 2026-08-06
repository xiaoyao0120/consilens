# UNIT-SQL-GENERATION 第 2 轮修复请求

- 上一轮：round-1
- Reviewer 结论：fail（Confidence: High, Risk Level: High）
- 主 Agent 结论：证据充分、已复核主要 Diff，接受 fail 并转为修复请求
- 阻塞问题：
  - Oracle checksum 聚合输入含 XML 标签，与 MySQL 纯文本拼接不一致，跨库比较必然失败。
  - Doris 小数进位不传播，进位边界值产生假差异。
  - ClickHouse NULL 整数在 toString 后丢失，与 MySQL COALESCE 语义不一致。
  - Presto DOUBLE/FLOAT 规范化丢失尾零，跨库 checksum 不一致。
  - TableSegment 生成 DATE/TIMESTAMP ANSI 字面量，SQL Server 语法错误。
  - Oracle/ClickHouse/Trino 模块 5 个既有测试断言未同步，mvn test 失败。
- 必需修改：
  1. `OracleSqlQueryGenerator.getChecksumSQLWithConcat`：改用与 MySQL 一致的纯文本聚合输入（避免 XML 标签进 hash），或两端统一归一化输入。
  2. `DorisDataTypeHandler.normalizeDecimal`：对舍入后完整值统一取整，保证进位传播；替换或限制 `(long) Math.pow(10, precision)` 溢出路径。
  3. `ClickHouseSqlQueryGenerator`：checksum/row_hash 拼接处对 NULL 补 `COALESCE(toString(...), '0')`，与 MySQL 对齐。
  4. `PrestoDataTypeHandler.normalizeDecimal/normalizeFloat`：恢复固定小数位输出（Presto `format()` 或手写补零）。
  5. `TableSegment.formatValue`：日期字面量按目标方言输出或回退带引号字符串，避免 SQL Server 语法错误。
  6. 同步更新 Oracle/ClickHouse/Trino 模块中与新行为冲突的测试断言。
- 需要检查的文件：以上对应文件及其直接调用链。
- 需要新增或重新运行的测试：受影响模块 `mvn test` 必须通过；修复后需补充进位、NULL、尾零的定向断言。
- 需要重复的集成检查：MySQL↔Oracle、MySQL↔Presto、MySQL↔ClickHouse、MySQL↔Doris checksum 语义一致性；SQL Server 日期分段 SQL。
- 约束：不 stage、不 commit、不 push；不得改动 UNIT-CROSS-DB-EXAMPLES 拥有的文件。
- 不得破坏：既有 MySQL/PostgreSQL/OceanBase 比较语义；`buildDiffColumnsExpression` 下游 `JoinDiffer` 解析。
- 下一轮必须提供的证据：`mvn test` 通过结果、进位/NULL/尾零定向测试断言、生成 SQL 样例。
