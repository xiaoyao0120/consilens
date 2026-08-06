# UNIT-SQL-GENERATION 工作日志

- round-2 开始：沿用 round-1 Review 证据，修复范围以最新 scope 和 repair-request 为准。

## 关键源码与测试调研

- MySQL 基准：`MySQLSqlQueryGenerator.getChecksumSQLWithConcat` 用 `GROUP_CONCAT(row_checksum ORDER BY pk_key SEPARATOR '|')`，row_checksum 为 `MD5(CONCAT_WS('|', 归一化列))`；`MySQLDataTypeHandler.normalizeDecimal` 用 `REPLACE(FORMAT(col, p), ',', '')` 保留尾零。Oracle/Doris/ClickHouse/Presto 必须与此语义对齐。
- `BaseDataTypeHandler.normalizeColumn`：DECIMAL 与 DOUBLE 都走 `normalizeDecimal`，FLOAT/REAL 走 `normalizeFloat`；因此 Presto 尾零修复同时覆盖 DOUBLE 与 DECIMAL 列。
- `TableSegment.formatValue` 调用链：`buildWhereClause()` 仅用 `keyColumns`/`minKey`/`maxKey`；`database.getConnectorType()` 可区分方言（sqlserver/mssql）。core 层无其他 formatValue 调用方。
- `consilens-core` 现有 Mockito 测试在本环境全部因 `MockMaker` 初始化失败（环境性问题，与本次改动无关）；TableSegmentTest 新测试改用 JDK Proxy 避免依赖。
- seed/load SQL 只创建 `mydb.orders_backup`（`examples/mysql-oceanbase/load-{mysql,oceanbase}.sql`），两个 OceanBase 测试原引用 `mydb.orders_target` 指向不存在的表。

## 修改决策

1. Oracle checksum：`XMLAGG(XMLELEMENT(E, row_checksum || '|'))` 会注入 `<E>...</E>` 标签破坏与 MySQL 的 hash 一致性；改为 `EXTRACT(..., '//text()').GETCLOBVAL()` 去标签纯文本聚合，保留 XMLAGG 的 CLOB 能力（LISTAGG 4000 字节限制）与 DBMS_CRYPTO 大输入。
2. Doris `normalizeDecimal`：round-1 手写“整数部分 FLOOR + 小数部分单独乘 10^p”导致进位不传播（-1.99999 p=4 输出 -1.10000），且 `(long) Math.pow(10, precision)` 在 p>18 溢出。改为先 ROUND/TRUNCATE 整体，再 `CAST(... AS DECIMAL(38, min(p,30)))` 定标，最后 CAST AS CHAR 保留尾零；Doris 无 `CAST AS INT`，旧路径本身不可用。
3. ClickHouse：`normalizeInteger` 返回原列（round-1 为规避 WHERE 类型错误），checksum/row_hash 拼接处 `toString(...)` 遇 NULL 返回 NULL，`concat` 整体 NULL；统一包 `COALESCE(toString(...), '0')` 与 MySQL 对齐。
4. Presto：`CAST(ROUND(x,p) AS VARCHAR)` 丢尾零（1.5 而非 1.5000）；恢复 `FORMAT('%.Nf', ...)` 固定小数位，与 Trino 现有实现一致。
5. TableSegment：ANSI 日期字面量对 SQL Server 语法错误；按 `database.getConnectorType()` 判断 sqlserver/mssql 输出带引号字符串，其余方言保留 ANSI 字面量。LocalDateTime 的 'T' 分隔符统一替换为空格（Presto/Trino TIMESTAMP 字面量要求空格）。
6. 测试：同步 Oracle（LISTAGG→EXTRACT 去标签）、ClickHouse DataTypeHandler（原列）、ClickHouse SqlQueryGenerator（arrayStringConcat + NULL 归一化）、Trino DataTypeHandler（原列）；新增进位、NULL、尾零、SQL Server 日期分段定向断言。
7. OceanBase 测试：两个测试 target 表名统一改为 `mydb.orders_backup`（seed 真实存在的表），与示例契约一致。

## round-3 修改决策（本轮）

1. Oracle 聚合去尾 `|`：EXTRACT 拼接后文本形如 `md5hex1|md5hex2|...|`，与 MySQL 无尾分隔符不一致；改为 `COALESCE(RTRIM(EXTRACT(...).GETCLOBVAL(), '|'), '')`，空表时 COALESCE 为 `''` 与 MySQL 对齐。
2. Oracle diff_columns NULL 传播：Oracle `||` 遇 NULL 当空串（非跳过），Base `buildDiffColumnsExpression` 的 `CASE ... ELSE NULL` 会产生 `[, , "col", ]` 残余分隔符。重写 Oracle `buildDiffColumnsExpression`：每个变化列输出前导 `', "col"'`，`LTRIM(..., ', ')` 去掉首个分隔符，再包 `'[' || ', ' || ... || ', ' || ']'`，与 MySQL `CONCAT_WS` 输出逐字节一致；stringJoin 保持原样（无其他调用方，注释说明）。
3. Doris 去 DOUBLE：`CAST(col AS DOUBLE)` 对 DECIMAL(38,N) 有 53 位尾数精度损失；直接 `ROUND(col, p)`/`TRUNCATE(col, p)` 作用于 DECIMAL 列，precision=0 分支同步去掉 DOUBLE。
4. Oracle DBMS_CRYPTO 授权：javadoc 补充 `GRANT EXECUTE ON sys.dbms_crypto TO <user>;`。
5. OceanBaseSameDbComparisonTest 注释同步为 orders_backup。

## 未实机验证项

- Oracle `EXTRACT(...)//text()` + `RTRIM`、Doris `DECIMAL(38,30)`、Presto `FORMAT('%Nf')` 均需真实数据库联调确认；当前无外部数据库环境。
