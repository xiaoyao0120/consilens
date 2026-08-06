# 工作拆分

## UNIT-SQL-GENERATION：核心 SQL 生成与分段逻辑

- 目标：修复 SQL checksum、数值规范化与 TableSegment 方言问题，同步测试并证明 MySQL 与目标端文本语义一致。
- 依赖：现有 connector SPI、配置模型与比较执行链路。
- 拥有的产品代码文件或目录：6 个 SQL/DataType 生成器、`TableSegment.java`、ClickHouse `pom.xml`、2 个 OceanBase 测试。
- 允许修改的辅助文件：本单元事实源和对应模块定向测试。
- 禁止修改的文件：`consilens-cli/**`、`examples/**`、`deploy/**`、`.gitignore`。
- 共享契约：生成 SQL 的字段别名、差异列 JSON-like 字符串、分段上下界。
- 验收标准：round-2 六项必需修改完成；进位、NULL、尾零、Oracle 聚合、SQL Server 日期分段均有测试证据。
- 必需验证：受影响 connector/core 模块定向 Maven 测试通过。
- 主要风险：High（SQL 方言、数据正确性、边界计算、依赖升级）。
- 分配的实现者：独立实现 Agent（round-2）。
- 执行顺序：独立于 `UNIT-CROSS-DB-EXAMPLES`。

## UNIT-CROSS-DB-EXAMPLES：跨库集成测试与示例

- 目标：修复集成测试、CLI 可执行产物、运行脚本、YAML/SQL fixture 和凭据注入问题。
- 依赖：CLI 命令、配置反序列化、各 connector type 与外部数据库。
- 拥有的产品代码文件或目录：`consilens-cli/pom.xml`、`CrossDatabaseComparisonIntegrationTest.java`、`examples/mysql-*`、`examples/run-comparison-test.sh`、3 个重命名文件、`.gitignore`。
- 允许修改的辅助文件：本单元事实源。
- 禁止修改的文件：SQL 生成器、DataTypeHandler、`TableSegment.java`、两个 `OceanBase*ComparisonTest.java`。
- 共享契约：CLI JAR 路径、Maven 测试发现、YAML schema、环境变量、fixture 表字段。
- 验收标准：CLI 入口可由 Maven 产物运行；脚本的数据装载声明与行为一致；SQL/YAML 契约、凭据注入和测试启用机制修复。
- 必需验证：CLI package/test-compile、fat jar smoke test、`bash -n`、脚本 dry-run/静态契约检查。
- 主要风险：Medium（大量外部依赖和重复配置，主要影响可验证性与示例可用性）。
- 分配的实现者：独立实现 Agent（round-2）。
- 执行顺序：独立于 `UNIT-SQL-GENERATION`。
