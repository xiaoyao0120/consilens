# 任务简报

## 用户请求

先使用 `medium-dev-loop` 对当前代码进行 Review 并输出完整报告；随后按用户“开始开发”的指令，依据 round-2 修复请求实施修复、验证、独立复审和最终验收。

## 目标

修复 round-1 Review 已确认的 SQL 生成、跨库比较语义、示例脚本、fixture 与集成测试问题，使受影响模块测试通过，并使示例入口在具备外部数据库前置条件时可执行。

## 范围内

- 9 个未暂存的 Java/POM 修改。
- 3 个已暂存的文件重命名。
- 新增 `CrossDatabaseComparisonIntegrationTest`。
- `examples/mysql-*` 下新增的 YAML、SQL 与 `run-comparison-test.sh`。
- round-2 修复所需的定向单元测试、`consilens-cli/pom.xml` 与 `.gitignore`。

## 范围外

- `consilens-cli/output/*.csv`：运行生成物，只分类，不逐文件审查。
- 与当前工作区 Diff 无直接关系的历史代码。
- 创建、修改或连接用户未授权的外部数据库环境。
- stage、commit、push 或创建 Pull Request。

## 预期行为

- SQL 生成在 MySQL、Presto、Trino、SQL Server 等方言下保持语法与结果语义正确。
- 分段计算不会产生零步长、死循环或错误边界。
- 跨库测试与示例引用真实存在的配置、模块和构建产物。
- 新增 SQL/YAML 的表结构、字段映射和连接参数前后一致。
- CLI 运行入口与 Maven 构建产物一致，凭据仅通过环境变量或系统属性注入。

## 验收标准

- 两个实现单元完成 round-2 必需修复和定向测试。
- 每个单元由独立 Reviewer 给出 `pass`，并由主 Agent `accept`。
- 受影响模块测试、脚本语法、YAML/SQL 契约与 `git diff --check` 通过。
- 外部数据库未实际联调的范围、前置条件与剩余风险明确披露。

## 兼容性要求

- API：无显式公共 HTTP API 变化。
- 数据库：不得生成目标方言不支持或语义不一致的 SQL。
- 前端：不适用。
- 数据：示例加载 SQL 不应破坏比较数据契约。
- 部署：文件重命名后既有引用必须同步。

## 验证要求

- 单元测试：运行与修改模块匹配的定向 Maven 测试。
- 集成测试：检查测试发现机制与外部数据库前置条件；能运行时再执行。
- 前端检查：不适用。
- SQL 或 Migration 检查：静态检查新增 SQL、YAML、脚本引用及方言。
- 构建或类型检查：至少编译受影响模块。
- 手工检查：检查关键生成 SQL 与示例路径。

## 事实源优先级

1. 用户明确指令。
2. 对话中提供的仓库 `AGENTS.md` 规则。
3. 当前工作区 Diff、代码和测试。
4. 保守、安全的合理假设。
