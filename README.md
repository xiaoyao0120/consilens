# Consilens

> 跨数据源数据一致性校验工具。

Consilens 用于验证数据同步、迁移、ETL、回填后的结果是否一致，并输出行级、列级差异。

适合这类场景：

- MySQL → PostgreSQL / ClickHouse / StarRocks 迁移验收
- 数据同步链路校验
- ETL / 回填结果核对
- 双写 / 灰度切换对账

## 核心特性

- 跨数据源比对，不要求两侧数据集在同一个实例
- 基于 connector/dataset 抽象，架构可扩展至 ES、MongoDB、HDFS 等非关系型数据源
- 大表优先走 checksum 收敛，差异可定位到主键和字段
- 支持多格式 sink 输出：控制台、JSON 文件、CSV 文件、结果表
- 基于 SPI 的连接器插件扩展

## 内置连接器

| 数据库 | 连接器模块 | 验证状态 |
| --- | --- | --- |
| MySQL | `consilens-connector-mysql` | 已验证 |
| PostgreSQL | `consilens-connector-postgresql` | 已验证 |
| SQL Server | `consilens-connector-sqlserver` | 内置（待验证） |
| Oracle | `consilens-connector-oracle` | 内置（待验证） |
| ClickHouse | `consilens-connector-clickhouse` | 内置（待验证） |
| Doris | `consilens-connector-doris` | 内置（待验证） |
| StarRocks | `consilens-connector-starrocks` | 已验证 |
| Presto | `consilens-connector-presto` | 内置（待验证） |
| Trino | `consilens-connector-trino` | 内置（待验证） |
| TiDB | `consilens-connector-tidb` | 内置（待验证） |

说明：`内置（待验证）` 表示仓库里已有对应连接器模块，但 README 目前只把已完成端到端验证的数据库作为对外首推能力。

## 工作方式

```text
边界探测
  ↓
首轮分段
  ↓
checksum 比对
  ↓
一致段跳过
  ↓
差异段继续收敛
  ↓
小段本地精确比较
```

## 快速开始

### 构建

```bash
./mvnw -B package
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -Pconsilens-mcp -pl consilens-mcp package
```

完整发布门禁可以直接运行：

```bash
scripts/release/release-check.sh
```

### 执行

```bash
./bin/consilens-cli.sh diff -c config.yaml
```

### AI Chat 闭环

如果你希望通过 chat/session 的方式完成 **配置生成 → 执行 → 结果判断 → 修复配置 → 再验证** 的闭环，当前正式入口是 `consilens-cli` 的 AI runtime：

```bash
./bin/consilens-cli.sh ai plan --session orders-loop "compare orders ..."
./bin/consilens-cli.sh ai --session orders-loop --config orders-loop.yaml --backend openai
```

闭环中的 config、run result、diff evidence、diagnosis、run audit、repair plan 和非敏感 memories 都会绑定到同一个 `--session`。推荐先用 `ai plan` 或 `ai run` 带结构化参数创建第一版 session，或者直接用 `ai --config <path> --backend ...` 把已有配置导入交互会话；进入 `ai` 后可继续用自然语言或 `/plan`、`/use-config`、`/validate`、`/dry-run`、`/run`、`/diff`、`/analyze-last`、`/repair`、`/remember`、`/forget`、`/recover`、`/artifacts [type]`、`/artifact <id>`、`/explain`、`/memories` 持续迭代。`ai shell` 仍可用，但只是兼容别名。

如果要让 OpenAI / DeepSeek / Ollama 等 backend 在 `ai plan`、`ai run`、`ai doctor`、`ai shell` 之间共享默认配置，建议把 backend/model/baseUrl/timeout 放在 `~/.consilens/ai/backend-defaults.json`（或 `$CONSILENS_AI_HOME/backend-defaults.json`），把密钥继续放在 `apiKeyEnv` 指向的环境变量里。

更完整的使用文档见：

- [consilens-cli/README.md](./consilens-cli/README.md) 中的 **AI Chat 闭环使用文档**
- [consilens-ai/USAGE.md](./consilens-ai/USAGE.md) 中的 runtime closed-loop guide

### 运维/值班最简步骤

适合已经有 session 或者只想快速完成一轮修复验证：

```bash
# 1) 看运行时插件和后端是否正常
./bin/consilens-cli.sh ai doctor

# 2) 首次建 session（也可以换成已有 session）
./bin/consilens-cli.sh ai plan --session orders-loop "compare orders ..." --dry-run

# 3) 真正执行并自动拿到 diagnosis
./bin/consilens-cli.sh ai run --session orders-loop --approve-execute

# 4) 基于最新 diagnosis 重生配置
./bin/consilens-cli.sh ai repair --session orders-loop -o orders-loop-repaired.yaml

# 5) 再跑一轮确认修复是否收敛
./bin/consilens-cli.sh ai run --session orders-loop --approve-execute

# 6) 回看记忆和上下文
./bin/consilens-cli.sh ai memories --session orders-loop
```

### 最小配置示例

```yaml
source:
  type: mysql
  connection:
    url: jdbc:mysql://localhost:3306/source_db
    username: ${env.MYSQL_USER}
    password: ${env.MYSQL_PASSWORD}
  resource:
    type: table
    name: orders

target:
  type: postgresql
  connection:
    url: jdbc:postgresql://localhost:5432/target_db?currentSchema=public
    username: ${env.PG_USER}
    password: ${env.PG_PASSWORD}
  resource:
    type: table
    name: orders

comparison:
  keys:
    source:
      - order_id
    target:
      - order_id
  fields:
    source:
      - order_id
      - customer_id
      - amount
      - status
      - created_at
    target:
      - order_id
      - customer_id
      - amount
      - status
      - created_at

strategy:
  mode: checksum
  algorithm: xor
  bisectionFactor: 8
  bisectionThreshold: 5000
  batchSize: 1000
  enableProfiling: false

result:
  sinks:
    - format: console
      type: result
    - format: json
      type: diff-record
      properties:
        path: ./diff_results.json
        pretty: true
```

请将上面的用户名和密码替换成真实值。配置支持环境变量占位符，语法为 `${env.VAR_NAME}` 或 `${env.VAR_NAME:默认值}`。

## 策略

| 策略 | 状态 | 说明 |
| --- | --- | --- |
| `checksum` | 已实现 | 推荐默认使用，支持跨数据源 |
| `join` | 已实现 | 仅支持同一个 JDBC URL |

## 文档

- [快速开始](./docs/01-快速开始.md)
- [配置详解](./docs/02-配置详解.md)
- [连接器与数据源支持](./docs/03-插件与数据库支持.md)
- [架构设计](./docs/04-架构设计.md)
- [开发指南](./docs/05-开发指南.md)

## 社交媒体

- 微信公众号，扫描二维码关注

![微信二维码](docs/img/wechat-qrcode.png)

## 联系作者

- 添加时备注：Consilens

![wechat-author-qrcode](docs/img/wechat-author-qrcode.png)
