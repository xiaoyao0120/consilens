# UNIT-SQL-GENERATION 范围

## 目标

修复核心 SQL 生成、数据类型规范化和分段方言问题，并同步定向测试。

## 拥有的文件

- `consilens-connector/**/BaseSqlQueryGenerator.java`
- `consilens-connector/**/PrestoDataTypeHandler.java`
- `consilens-connector/**/{Presto,Trino,SQLServer}SqlQueryGenerator.java`
- `consilens-connector/**/consilens-connector-clickhouse/pom.xml`
- `consilens-core/src/main/java/com/consilens/core/segment/TableSegment.java`
- 两个 `OceanBase*ComparisonTest.java`

## 允许修改的辅助文件

- 本单元事实源和对应模块定向测试。

## 禁止修改的文件

- `consilens-cli/**`、`examples/**`、`deploy/**`、`.gitignore`。

## 必需行为

- 完成最新 `repair-request.md` 的六项必需修改。
- 两个 OceanBase 测试统一引用 seed 中真实存在的 `orders_backup`，不得创建无来源的 `orders_target`。

## 验收标准

- 进位、NULL、尾零、Oracle 聚合与 SQL Server 日期分段均有测试证据，受影响模块测试通过。

## 必需验证

- 定向 Maven 验证或明确说明无法运行的原因。

## 依赖

- connector API、比较执行器、结果消费逻辑。

## 主要风险

- SQL 方言、数值语义、整数溢出/零步长、JDBC 兼容性。

## 非目标

- 不泛化重构全部 connector，不改跨库示例单元拥有的文件。
