# UNIT-SQL-GENERATION 第 1 轮 Review

- Verdict: fail
- Confidence: High
- Risk Level: High
- 风险等级理由：改动覆盖 SQL 方言适配、跨库 checksum 语义、数值规范化与依赖升级，且定向测试出现失败；多个发现会导致跨库比较结果错误或 SQL 语法错误。
- 已阅读的必读文件：
  - `task-brief.md`
  - `units/UNIT-SQL-GENERATION/scope.md`
  - `units/UNIT-SQL-GENERATION/implementer/change-summary.md`
  - `units/UNIT-SQL-GENERATION/implementer/validation.md`
  - 当前工作区 Diff（`git diff` 相对 HEAD `4bbbd1f`）
  - 对话提供的 `AGENTS.md`
- 按需阅读的文件：
  - `implementation-plan.md`
  - `work-breakdown.md`
  - `BaseDataTypeHandler.java`、`MySQLDataTypeHandler.java`（规范化基准）
  - `JoinDiffer.java`（diff_columns 下游解析）
  - `TableSegment.java`（formatValue 调用链）
  - 各 connector 的 `SqlQueryGenerator`/`DataTypeHandler` 现文件
- 已检查的主要修改（100%）：
  1. `BaseSqlQueryGenerator.java`：`stringJoin` 扩展点、`buildDiffColumnsExpression` 重构
  2. `PrestoDataTypeHandler.java` + `PrestoSqlQueryGenerator.java`
  3. `TrinoDataTypeHandler.java` + `TrinoSqlQueryGenerator.java`
  4. `ClickHouseDataTypeHandler.java` + `ClickHouseSqlQueryGenerator.java` + `pom.xml`
  5. `DorisDataTypeHandler.java` + `DorisSqlQueryGenerator.java`
  6. `OracleSqlQueryGenerator.java`
  7. `SQLServerSqlQueryGenerator.java`
  8. `TiDBSqlQueryGenerator.java`
  9. `TableSegment.java`
  10. `OceanBaseCrossDbComparisonTest.java` + `OceanBaseSameDbComparisonTest.java`
- 已检查的高风险文件：以上全部（SQL 生成与跨库语义相关）
- 已抽样的辅助修改：无
- 未检查内容及原因：与 UNIT-CROSS-DB-EXAMPLES 重叠的示例/YAML/集成测试不在本单元范围；`consilens-cli/output/*.csv` 为运行生成物，仅分类。
- Validation 评估：实现者未提供任何验证记录（`validation.md` 明确标注"实现者未提供验证记录"），与改动风险不匹配，需要 Reviewer 自行补证。
- 是否重新运行测试：yes
- 重新运行的命令：
  - `./mvnw -q -pl <presto,trino,clickhouse,doris,oracle,sqlserver,tidb>,consilens-core -am -DskipTests compile` — pass（全部受影响模块编译通过，ClickHouse 0.9.8 依赖可解析，本地仓库存在 `clickhouse-jdbc-0.9.8-all.jar`）
  - `./mvnw -q -pl <presto,trino,clickhouse,oracle,base> -am test` — fail（详见"主要发现"）
  - `./mvnw -q -pl <presto,doris,sqlserver,tidb> test` — pass
- 已尝试的风险场景：
  1. Oracle `stringJoin` 的 NULL 传播与 `XMLAGG` 聚合输入
  2. Doris 手写 decimal 格式化的进位传播与 `(long)Math.pow(10, precision)` 溢出
  3. ClickHouse `normalizeInteger` 返回原列后 `toString()` 的 NULL 行为
  4. Presto `CAST(ROUND(x,p) AS VARCHAR)` 的尾零保留
  5. `TableSegment.formatValue` 的 `DATE '...'`/`TIMESTAMP '...'` 字面量方言兼容性
  6. Base `CONCAT_WS` 与 Presto/Trino `ARRAY_JOIN` 对 NULL 元素的处理一致性

## 主要发现

### P1-1 Oracle checksum 聚合输入与 MySQL 不一致（跨库比较必然失败）

`OracleSqlQueryGenerator.getChecksumSQLWithConcat` 改为：

```sql
DBMS_CRYPTO.HASH(XMLAGG(XMLELEMENT(E, row_checksum || '|') ORDER BY pk_key).GETCLOBVAL(), 2)
```

`XMLELEMENT(E, ...)` 会生成 `<E>...</E>` XML 标签，`GETCLOBVAL()` 返回的是含标签的 XML 文本，即 hash 输入形如 `<E>md5hex1|</E><E>md5hex2|</E>`。而 MySQL 端（`MySQLSqlQueryGenerator`）的 checksum 输入是纯 `md5hex1|md5hex2|`。跨库比较时两端聚合输入不同，checksum 必然永不匹配，所有行都会被报为差异。原 `LISTAGG` 方案与 MySQL 拼接语义一致；本次为规避 4000 字节限制引入 XML 包装，破坏了跨库一致性。

### P1-2 Doris `normalizeDecimal` 进位不传播

`DorisDataTypeHandler.normalizeDecimal` 中整数部分取 `FLOOR(ABS(ROUND(col, p)))`，小数部分单独取 `ROUND((ABS(col) - FLOOR(ABS(col))) * 10^p)`。当小数部分舍入进位（例如 `col = -1.99999, precision=4`：小数部分 `ROUND(0.99999 * 10000) = 10000`）时，整数部分不会 +1，输出 `-1.10000`，而 MySQL 端 `FORMAT(ROUND(-1.99999, 4), 4)` 输出 `-2.0000`。跨库 checksum 不一致，产生假差异。该公式对小数值恰好落在进位边界时触发（`ABS(col)` 未先 ROUND 就取小数，加剧了不一致）。

### P1-3 ClickHouse NULL 整数归一化丢失

`ClickHouseDataTypeHandler.normalizeInteger` 改为返回原列（为规避 WHERE 类型错误），由 `ClickHouseSqlQueryGenerator` 包 `toString(...)`。ClickHouse 的 `toString(NULL)` 返回 NULL，`concat` 遇 NULL 整体为 NULL，`MD5(NULL)` 为 NULL。而 MySQL 端 `normalizeInteger` 有 `COALESCE(..., '0')`，NULL 归一化为 `'0'`。两端对含 NULL 整数列的行，row_checksum 一侧为 NULL、一侧为 `MD5('0|...')`，必然不一致。`getChecksumSQL` 与 `getRowHashSQL` 均受影响。

### P1-4 Presto 数值规范化丢失尾零

`PrestoDataTypeHandler.normalizeDecimal`/`normalizeFloat` 从 `FORMAT('%0.4f', ...)` 改为 `CAST(ROUND(x, p) AS VARCHAR)`。Presto 的 `CAST(DOUBLE AS VARCHAR)` 不保留小数位（`CAST(1.5 AS VARCHAR) = '1.5'`），而 MySQL 侧 `FORMAT(..., 4)` 输出 `'1.5000'`。由于 `normalizeColumn` 中 `DOUBLE` 也走 `normalizeDecimal`，DOUBLE/FLOAT 列在 Presto 与 MySQL 之间的 checksum 必然不一致。注释称"Presto has no FORMAT()"不准确：Presto 提供 `format()` 函数；且 DECIMAL 输入因保留 scale 不受影响，只有 DOUBLE/FLOAT 受影响。

### P1-5 `TableSegment.formatValue` 生成 SQL Server 不支持的日期字面量

新增分支对日期/时间字符串与 `LocalDate`/`LocalDateTime` 输出 ANSI 字面量 `DATE '...'` / `TIMESTAMP '...'`。SQL Server 不支持该语法（其日期字面量是带引号字符串），使用日期类型 key 分段时生成 `WHERE col >= DATE '2026-05-01'` 会直接语法错误。原实现输出 `'2026-05-01'`（字符串，SQL Server 可隐式转换），本次为兼容 Presto/Trino 引入的改动对 SQL Server 是回归。`formatValue` 在 `core` 层对所有方言共用，未做方言判断。

### P2-1 行为变更未同步更新测试，受影响模块 `mvn test` 失败

定向运行测试结果：

- `OracleSqlQueryGeneratorTest.testGetChecksumSQL` 断言 `STANDARD_HASH`/`LISTAGG`，新实现为 `DBMS_CRYPTO`/`XMLAGG`，失败。
- `ClickHouseDataTypeHandlerTest.testNormalizeColumn_Int`、`testNormalizeColumn_Timestamp` 断言旧 `normalize` 输出，新实现返回原列，失败。
- `ClickHouseSqlQueryGeneratorTest.testGetChecksumSQL` 断言 `groupConcat`，新实现为 `arrayStringConcat`，失败。
- `TrinoDataTypeHandlerTest.testNormalizeColumn_Date` 断言 `FORMAT_DATETIME`，新实现返回原列，失败。

这些失败说明测试契约未随行为变更同步，属于构建验证失败；若修复实现必须一并对齐断言。

### P2-2 Doris `(long) Math.pow(10, precision)` 溢出

`precision > 18` 时 `(long) Math.pow(10, precision)` 溢出为 `Long.MAX_VALUE`，小数部分乘数失真，随后 `CAST(... AS INT)` 也会溢出。Doris DECIMAL 支持到 precision 38，配置项未限制上限。

### P2-3 Oracle 依赖 `DBMS_CRYPTO` EXECUTE 权限

`DBMS_CRYPTO.HASH` 需要 `EXECUTE` 权限，注释声称"granted to consilens user"，但示例/文档未提供授权语句，部署到新环境会运行失败。

### P3 次要问题

- `ClickHouseSqlQueryGenerator` 中 `toString(formatForChecksum(...))` 对已返回字符串的日期分支形成双重 `toString`，冗余但无害。
- `OceanBase*ComparisonTest` 从精确断言（`assertEquals(0, ...)`）弱化为 `assertTrue(... > 0)`，丢失了对差异数量的验证；注释称 bisection 导致数量可能波动，可接受但降低了测试价值。
- Base `buildDiffColumnsExpression` 分隔符从 `','` 改为 `', '`（带空格），`JoinDiffer.parseDiffColumns` 使用 `split(",")` + `trim` 解析，兼容；Presto/Trino 的 `ARRAY_JOIN` 两参版本跳过 NULL 元素，与 `CONCAT_WS` 一致，此扩展点设计正确。
- `TiDBSqlQueryGenerator.getRowHashSQL` 与 MySQL 实现一致，无问题。
- `SQLServerSqlQueryGenerator` 的 `CAST(row_checksum AS VARCHAR(MAX))` 修复了 STRING_AGG 8000 截断，正确。
- `OracleSqlQueryGenerator.getChecksumSQLWithXor` 的 `SUM` 溢出：16 位 hex 最大约 1.8e19，NUMBER 精度 38 位可容纳，列数在合理范围内不会溢出，非阻塞。

## 兼容性或回归风险

- MySQL↔Oracle 跨库比较在 Oracle 侧使用 `DBMS_CRYPTO` + XMLAGG 后，checksum 输入与 MySQL 不一致，功能失效（P1-1）。
- MySQL↔Presto 对 DOUBLE/FLOAT 列比较产生系统性假差异（P1-4）。
- MySQL↔ClickHouse 对含 NULL 整数列比较产生系统性假差异（P1-3）。
- Doris 对进位边界值比较产生假差异（P1-2）。
- SQL Server 日期 key 分段 SQL 语法错误（P1-5）。
- ClickHouse JDBC 0.4.6 → 0.9.8：本地仓库存在 `0.9.8-all` 且编译通过；但代码库中未见驱动类名引用（`ClickHouseConnectorProvider` 依赖 SPI 加载），运行时 URL/驱动注册未在本单元验证。

## 缺失证据

- 实现者未提供任何验证记录。
- Oracle `DBMS_CRYPTO` 权限的授权语句未见（可能在示例 SQL 中，属于 UNIT-CROSS-DB-EXAMPLES 范围）。
- ClickHouse 驱动升级后的实际连接测试未运行（需要外部数据库）。

## 接受的非阻塞缺口及原因

- `TrinoSqlQueryGenerator.formatForChecksum` 对 `date`/`datetime`/`timestamp` 的分支判断基于 `dataType.name().toLowerCase()` 的字符串包含，`TIMESTAMP_WITH_TIMEZONE` 可正确落到 `timestamp_with_timezone` 分支；逻辑正确，保留。
- Base `buildDiffColumnsExpression` 输出格式变化（带空格）已确认下游解析兼容。

## Required Changes

1. **Oracle**：`getChecksumSQLWithConcat` 改用与 MySQL 一致的纯文本聚合输入（例如 `LISTAGG(...) ON OVERFLOW TRUNCATE` 或先 XML 解析再拼接），或明确两端使用同一归一化输入；不得把 XML 标签纳入 hash 输入。
2. **Doris**：`normalizeDecimal` 对舍入后的完整值统一取整（对 `ROUND(col, p)` 的绝对值先取整再拆分），保证进位传播到整数部分；同时限制或替换 `(long) Math.pow(10, precision)` 的溢出路径。
3. **ClickHouse**：`normalizeInteger` 返回原列的同时，在 checksum/row_hash 拼接处补充 `COALESCE(toString(...), '0')` 之类的 NULL 归一化，与 MySQL 的 `'0'` 语义对齐。
4. **Presto**：数值规范化恢复固定小数位输出（Presto `format()` 或手写补零），确保 DOUBLE/FLOAT 与 MySQL `FORMAT` 一致。
5. **TableSegment**：`DATE '...'`/`TIMESTAMP '...'` 字面量需按目标方言输出，或回退为带引号字符串，避免 SQL Server 语法错误。
6. **测试**：同步更新 Oracle/ClickHouse/Trino 三个模块中与新行为冲突的断言，使 `mvn test` 通过。
