# UNIT-CROSS-DB-EXAMPLES 第 2 轮修复请求

- 上一轮：round-1
- Reviewer 结论：fail（Confidence: High, Risk Level: High）
- 主 Agent 结论：证据充分，关键 P0 已复核复现，接受 fail 并转为修复请求
- 阻塞问题：
  - `run-comparison-test.sh` 的 CLI JAR 路径与构建系统不符（Gradle 路径/产物 vs Maven 薄 jar），示例 100% 不可运行。
  - 脚本仅装载 MySQL 源库，10 个目标库 `load-*.sql` 均未执行，目标数据装载缺失。
  - `mysql-oceanbase/load-oceanbase.sql:103` 与 `mysql-presto/load-presto-target.sql:105` 引号错位，INSERT 语法错误。
  - Presto 01/03 YAML target 库与装载库 `consilens_demo_target` 不一致。
  - 脚本与测试类硬编码 22 个明文数据库凭据。
  - 主 Agent 复核新增：两个 OceanBase 测试引用 `mydb.orders_target`，但全部 seed/load SQL 只创建 `orders_backup`，测试指向不存在的表。
- 必需修改：
  1. 修正脚本 JAR 路径为 Maven 产物并提供可运行 fat jar 或 classpath 方案；同步修正构建提示。
  2. 为每个目标库实现数据装载，或删除"loads data into source and target databases"声明并明示人工装载。
  3. 修复两处 load SQL 引号错位。
  4. 统一 Presto 01/03 的 target 库名（`consilens_demo_target`）。
  5. 凭据外置：脚本和测试类均改为环境变量/系统属性读取，仓库内不保留真实或环境特定密码默认值。
  6. 通过 `.gitignore` 忽略 `examples/mysql-doris/*.bak`，保留用户未跟踪文件。
  7. 集成测试启用机制改为实际可用的 JUnit 条件/系统属性机制，删除误导的 Maven profile 声明。
  8. 人工确认 Doris 9031 端口（外部 compose 不在仓库内）；无法确认时参数化并披露。
- 需要检查的文件：CLI POM、脚本、全部 load SQL、Presto/Trino/OceanBase YAML、测试类、seed SQL、`.gitignore`。
- 需要新增或重新运行的测试：`mvn test` 默认跳过集成测试时至少 `test-compile` 通过；有外部数据库时运行 `run-comparison-test.sh` 至少一个 pair。
- 需要重复的集成检查：YAML 库名与 load SQL 装载目标一致；端口与外部 compose 一致；重命名路径无残留。
- 约束：不 stage、不 commit、不 push；不得改动 UNIT-SQL-GENERATION 拥有的文件。
- 不得破坏：现有 examples/configs 下的既有 YAML 引用与已经正确的 `deploy/mysql-init` 种子加载。两个 OceanBase 测试由 UNIT-SQL-GENERATION 修复，本单元不得修改。
- 下一轮必须提供的证据：修正后的脚本 dry-run 或单 pair 运行结果、SQL 语法校验、测试类凭据外置后的源码片段、旧路径残留扫描结果。
