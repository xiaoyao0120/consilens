# UNIT-CROSS-DB-EXAMPLES 范围

## 目标

修复跨数据库集成测试、CLI 产物、示例 YAML/SQL、运行脚本与凭据注入问题。

## 拥有的文件

- `consilens-cli/src/test/java/com/consilens/cli/config/CrossDatabaseComparisonIntegrationTest.java`
- `consilens-cli/pom.xml`
- `examples/mysql-*/*`
- `examples/run-comparison-test.sh`
- `deploy/mysql-init/01-seed-data.sql`
- `examples/configs/{cross-db,same-db}/*.yaml`
- `.gitignore`

## 允许修改的辅助文件

- 本单元事实源。

## 禁止修改的文件

- SQL 生成器、DataTypeHandler、`TableSegment.java`、两个 `OceanBase*ComparisonTest.java`。

## 必需行为

- 完成最新 `repair-request.md` 中属于本单元的必需修改。
- 不修改已经正确的 `deploy/docker-compose.yml` 挂载；`./mysql-init` 相对 `deploy/` 正确指向 `deploy/mysql-init/`。

## 验收标准

- CLI 可执行入口、脚本、测试启用机制、SQL/YAML 契约与凭据外置均有验证证据。

## 必需验证

- `bash -n`、配置解析或等价静态检查、Maven 测试发现检查。

## 依赖

- CLI、配置模型、数据库 connector 与外部数据库环境。

## 主要风险

- 测试未被发现、脚本引用错误产物、YAML/SQL 不一致、凭据泄露或错误默认值。

## 非目标

- 不连接或修改外部数据库，不改 SQL 生成单元文件。
