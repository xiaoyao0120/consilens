# Consilens CLI

Consilens CLI 是一个跨数据库数据一致性校验工具，通过 YAML/JSON 配置文件驱动，支持多种校验策略和输出方式。

## 功能特性

- **多数据库支持**：MySQL、PostgreSQL、Oracle、SQL Server、StarRocks、TiDB、Doris、ClickHouse、Presto/Trino 等 10 种数据库
- **双策略支持**：
  - `checksum`（默认）：基于分组校验和的递归二分对比，适合跨数据库、大数据量场景
  - `join`：基于 SQL FULL OUTER JOIN 的快速对比，适合同库或高速互联场景
- **多种 Checksum 算法**：`concat`（拼接，默认）、`xor`（异或）
- **本地比较可控**：终局小段默认使用完整行比较，必要时可显式启用主键 + 行哈希过滤
- **灵活输出（result.sinks）**：支持控制台摘要、JSON/CSV 文件以及写入数据库表，可同时配置多个 sink
- **配置文件驱动**：YAML 或 JSON 格式

## 快速开始

### 1. 构建并解包发行版

```bash
./mvnw -B clean package -Prelease
tar -xzf consilens-dist/target/consilens-*.tar.gz -C /opt/consilens
```

### 2. 生成配置模板

```bash
# 生成基础 YAML 模板
./bin/consilens-cli.sh config generate -o my-config.yaml

# 生成高级模板（包含输出到数据库表等完整选项）
./bin/consilens-cli.sh config generate -o advanced-config.yaml -t advanced

# 生成 JSON 格式模板
./bin/consilens-cli.sh config generate -o my-config.json -f json
```

### 3. 编辑配置文件

填入真实的数据库连接信息（详见[配置文件格式](#配置文件格式)）。

### 4. 验证配置

```bash
./bin/consilens-cli.sh config validate -c my-config.yaml
```

### 5. 执行数据对比

```bash
./bin/consilens-cli.sh diff -c my-config.yaml

# 使用详细日志
./bin/consilens-cli.sh diff -c my-config.yaml --verbose

# 仅做干运行（验证连接和行数，不执行实际对比）
./bin/consilens-cli.sh diff -c my-config.yaml --dry-run
```

## 命令参考

```
consilens <command> [options]

命令：
  config          配置管理
  diff            执行数据对比
  ai              AI 辅助配置生成、解释和诊断

选项：
  -h, --help      显示帮助信息
  -V, --version   显示版本信息
```

### `config generate`

```
./bin/consilens-cli.sh config generate -o <file> [选项]

选项：
  -o, --output    输出文件路径（必需）
  -f, --format    格式：yaml 或 json（默认：yaml）
  -t, --type      模板类型：basic 或 advanced（默认：basic）
```

### `config validate`

```
./bin/consilens-cli.sh config validate -c <file> [选项]

选项：
  -c, --config          配置文件路径（必需）
  --test-connection     测试数据库连接
  --verbose             显示详细验证结果
```

### `diff`

```
./bin/consilens-cli.sh diff -c <file> [选项]

选项：
  -c, --config    配置文件路径（必需）
  --dry-run       仅验证连接和行数，不执行实际对比
  --verbose       输出详细配置和进度信息
```

### `ai`

```
./bin/consilens-cli.sh ai plan --session ai-demo "compare orders" [选项]
./bin/consilens-cli.sh ai config "compare orders" -o ai-config.yaml [选项]
./bin/consilens-cli.sh ai diff "compare orders" -o ai-diff.yaml [选项]
./bin/consilens-cli.sh ai diff --execute --approve-execute "compare orders" [选项]
./bin/consilens-cli.sh ai run --session ai-demo --approve-execute
./bin/consilens-cli.sh ai repair --session ai-demo -o repaired.yaml
./bin/consilens-cli.sh ai explain -c my-config.yaml
./bin/consilens-cli.sh ai diagnose --result diff-records.json --analyzer rulebased --output diagnose.md
./bin/consilens-cli.sh ai memories --session ai-demo
./bin/consilens-cli.sh ai --session ai-demo
./bin/consilens-cli.sh ai shell --session ai-demo
./bin/consilens-cli.sh ai providers
./bin/consilens-cli.sh ai providers --format json
./bin/consilens-cli.sh ai doctor --format json
./bin/consilens-cli.sh ai --session orders-loop --config orders-loop.yaml --backend openai
```

当前正式的 runtime 闭环入口是 `ai`、`ai plan` 和 `ai run`；`ai shell` 保留为兼容别名，`ai config` / `ai diff` 保留为更轻量的生成与执行包装。`ai` / `ai shell` 现在可直接接收 `--config`、`--backend`、`--model`、`--base-url`、`--api-key`、`--timeout`、`--temperature`、`--max-tokens`、`--no-llm` 作为会话启动参数：如果传入 `--config`，启动时会先把现有配置装载进 session 并立即校验；默认模式下，`consilens ai` / `ai shell` 会在进入对话框前强校验 backend、base-url、api-key，缺任一项且又无法从 `backend-defaults.json` / 环境变量解析出来时会直接拒绝进入 REPL；只有 source/target/keys 等业务信息允许留到 chat clarification 阶段继续补齐。当自然语言已经足够完整时，同一批 startup backend 参数会自动注入到启动阶段的 plan 请求里。`ai repair` 会基于当前 session 的最新 diagnosis 重新生成修复后的配置，`ai memories` 用于查看持久化的非敏感运行记忆，交互模式支持自然语言输入以及 `/plan`、`/use-config <path>`、`/validate`、`/dry-run`、`/run`、`/diff`、`/analyze-last`、`/diagnose`、`/repair`、`/remember`、`/forget`、`/recover`、`/artifacts [type]`、`/artifact <id>`、`/explain`、`/sessions`、`/resume`、`/new`、`/config`、`/save <path>`、`/memories` 等命令。HTTP API 由 `consilens-server` 提供；MCP 入口由独立的 `consilens-mcp` 模块提供；Skills 以 `agent-skills/*/SKILL.md` 分发给 Agent 使用。CLI 不再承载 apiserver 或 MCP/Skills runtime。`ai diagnose` 需要 `json` + `diff-record` 输出文件，只有统计摘要的 result JSON 不包含行级证据，无法诊断。诊断 analyzer 通过 SPI 加载，可使用 `--analyzer` 或 `CONSILENS_AI_ANALYZER` 指定，默认是 `rulebased`；使用 `--output` 可将诊断报告写入文件。`ai providers` 用于确认运行时 classpath 中实际发现了哪些 analyzer 和 LLM backend 插件，并支持 `--format json` 供 CI 和脚本读取。`ai doctor` 用于生产前置检查，默认离线检查 provider、analyzer/backend 创建和云后端密钥配置。

LLM backend 的生产默认值建议统一放在 `~/.consilens/ai/backend-defaults.json`（或 `$CONSILENS_AI_HOME/backend-defaults.json`），这样 `ai plan` / `ai run` / `ai doctor` / `ai shell` 会共享同一份 backend、model、baseUrl、timeout 等默认配置；API key 推荐只在该文件里写 `apiKeyEnv`，真正的密钥继续通过环境变量注入。

## AI Chat 闭环使用文档

### 闭环目标

当前正式支持的 AI 闭环不是“聊天里直接生成 repair SQL 并执行”，而是围绕 **session runtime** 做这 5 步：

1. 通过自然语言和显式 hints 生成 Consilens 配置
2. 校验配置并执行 dry-run / real diff
3. 基于运行结果生成 diagnosis
4. 根据 diagnosis 重新生成修复后的配置
5. 回到 run 再验证，直到配置稳定

闭环中的配置、运行结果、diff evidence、diagnosis、repair plan 和非敏感 memories 都会绑定到同一个 `--session`。

### 推荐用法

生产场景推荐把闭环拆成两层：

1. **先用 `ai plan` / `ai run` 的结构化参数创建第一版 session**
2. **再进入 `ai --session ...` 用 chat 方式持续迭代**

这样既保留 chat 的多轮体验，也能在第一轮把数据库类型、JDBC URL、keys、fields、backend 等关键信息明确传进去。

### 0. 前置检查

先确认插件和后端装配正常：

```bash
./bin/consilens-cli.sh ai providers
./bin/consilens-cli.sh ai doctor
```

如果使用云 LLM，可通过环境变量提供默认值：

```bash
export CONSILENS_AI_BACKEND=openai
export OPENAI_API_KEY=...
```

也可以显式使用 `--backend openai|deepseek|ollama|noop`。如果希望完全不调用 LLM，只依赖显式 hints，可使用 `--no-llm`。

### 1. 创建第一版 session 和配置

推荐先用 `ai plan` 建立一个可复用 session：

```bash
./bin/consilens-cli.sh ai plan \
  --session orders-loop \
  "compare mysql orders with postgresql orders by order_id" \
  --source-type mysql \
  --source-url jdbc:mysql://mysql-prod:3306/shop \
  --source-table orders \
  --source-user-env MYSQL_USER \
  --source-password-env MYSQL_PASSWORD \
  --target-type postgresql \
  --target-url jdbc:postgresql://pg-staging:5432/shop \
  --target-table orders \
  --target-user-env PG_USER \
  --target-password-env PG_PASSWORD \
  --keys order_id \
  --fields status,amount,updated_at \
  --strategy-mode checksum \
  --algorithm xor \
  --dry-run \
  -o orders-loop.yaml
```

这一步会：

1. 生成 session 级 YAML 配置 artifact
2. 立即做 validate
3. 如果加了 `--dry-run`，再做一次 dry-run
4. 把当前配置写进 session，供后续 `/run`、`/repair`、`/explain` 复用

如果你已经很确定输入，也可以直接一步到位：

```bash
./bin/consilens-cli.sh ai run --session orders-loop --approve-execute \
  "compare mysql orders with postgresql orders by order_id" \
  --source-type mysql \
  --source-url jdbc:mysql://mysql-prod:3306/shop \
  --source-table orders \
  --target-type postgresql \
  --target-url jdbc:postgresql://pg-staging:5432/shop \
  --target-table orders \
  --keys order_id \
  --fields status,amount,updated_at
```

### 2. 进入 chat session

```bash
./bin/consilens-cli.sh ai --session orders-loop
```

进入后可以直接输入自然语言，但**生产闭环推荐优先使用显式 slash 命令**，因为自由文本目前仍是基于关键词的 intent router。

可用命令：

```text
/plan <goal>
/use-config <path>
/validate [path]
/dry-run [path]
/run [--approve-execute] <goal>
/diff [--approve-execute]
/analyze-last
/approve execute
/deny
/diagnose [path]
/repair
/remember <type> <content>
/forget <memory-id>
/recover
/artifacts [type]
/artifact <id>
/explain [path]
/snapshot
/memories
/help
/exit
```

### 3. 一个完整的 chat 闭环示例

```text
consilens ai> /snapshot
session=orders-loop config=<config-artifact> latestRun=<latest-run-or-null>

consilens ai> /explain
[AI RUNTIME] session=orders-loop
...解释当前配置、风险和执行方式...

consilens ai> /run
Pending approval created. Use `/approve execute` or `/deny`.
[AI RUNTIME] session=orders-loop
Diff execution requires explicit approval. Re-run with --approve-execute.

consilens ai> /approve execute
[AI RUNTIME] session=orders-loop
Run completed for session orders-loop ...
Diagnosis: ...
Repair Hints:
- ...

consilens ai> /repair
[AI RUNTIME] session=orders-loop
Created repair plan <repair-artifact> and regenerated config <new-config-artifact>

consilens ai> /explain
[AI RUNTIME] session=orders-loop
...解释修复后的当前配置...

consilens ai> /run --approve-execute
[AI RUNTIME] session=orders-loop
Run completed for session orders-loop ...

consilens ai> /memories
# Session Memories
- [goal] ...
- [diagnosis] ...
- [repair] ...
```

### 4. 每个 chat 命令在闭环里的作用

| 命令 | 作用 | 闭环中的典型时机 |
| --- | --- | --- |
| `/plan <goal>` | 生成并校验新的当前配置 | 第一次建配置，或想重写目标时 |
| `/validate [path]` | 对当前 session 配置（或指定 path）做结构与语义校验 | 想在不执行 dry-run 的前提下快速确认配置合法性时 |
| `/dry-run [path]` | 先 validate 再 dry-run，验证执行前环境和依赖 | 准备执行前做一次显式预检时 |
| `/run [--approve-execute] [goal]` | 校验当前配置、dry-run、真实执行 diff，并立即生成 diagnosis | 想验证当前配置是否已经可用时 |
| `/diff [--approve-execute]` | 不重新规划目标，直接基于当前配置进入执行闭环 | 已有稳定配置，只想重跑真实 diff 时 |
| `/analyze-last` | 诊断当前 session 最新 evidence（等价于无参 `/diagnose`） | diff 完成后快速复盘上一轮结果 |
| `/approve execute` | 对上一次待审批 `/run` 补充真实执行授权 | 先看 dry-run /提示，再决定是否真正执行 |
| `/diagnose [path]` | 诊断最新 session evidence；传 `path` 时可诊断外部结果文件 | 想更换 analyzer 或重跑诊断时 |
| `/repair` | 读取当前 session 最新 diagnosis，重新生成配置和 repair plan artifact | run 完成并拿到 diagnosis 后 |
| `/remember <type> <content>` | 手工保存一条非敏感 memory（`connection` / `project` / `preference`） | 想把当前经验显式固化到后续会话时 |
| `/forget <memory-id>` | 删除一条已保存 memory | 想清理过时上下文或误存的运行记忆时 |
| `/use-config <path>` | 将已有 YAML 配置装入当前 session，并立即生成 validation artifact | 已经手里有配置文件，但想切回 chat 闭环继续 validate / run / repair 时 |
| `/recover` | 输出当前 session 的恢复摘要、最新 diagnosis/audit 指针、artifact 路径和建议下一步 | shell 或进程中断后恢复闭环上下文时 |
| `/artifacts [type]` | 查看当前 session 最近的 artifact 索引，可按类型过滤 | 想追 run audit、diagnosis、repair patch lineage 时 |
| `/artifact <id>` | 直接查看指定 artifact 的正文内容 | 已经知道 artifact ID，需要取回详情时 |
| `/explain [path]` | 解释当前 session 配置；传 `path` 时解释指定配置文件 | repair 后复查配置变化时 |
| `/snapshot` | 查看当前 session 绑定的 config / latest run | 判断闭环当前停在哪一步 |
| `/memories` | 查看 session 的非敏感运行记忆 | 连续多轮迭代时确认历史上下文 |

### 5. 闭环里的几个关键语义

1. **`--session` 是闭环主键**  
   只要 session ID 不变，`ai plan`、`ai run`、`ai repair`、`ai explain`、`ai shell` 就会共享同一组 artifacts 和 memories。

2. **`ai run` 不带 goal 时会复用当前配置**  
   如果 session 已经有 `currentConfigArtifactId`，再次执行 `ai run --session <id> --approve-execute` 会直接拿当前配置重跑。

3. **`ai run` 带 goal 或 hints 时会覆盖当前配置**  
   适合你想边执行边改配置的场景，但如果只是复验修复结果，建议直接复用已有 session 配置。

4. **`ai repair` 修的是配置，不是直接执行 SQL**  
   当前正式设计里，repair 阶段会基于最新 diagnosis **重新生成更合理的 YAML 配置**，然后你再通过 `ai run` 验证这版配置是否把问题收敛。

5. **自由文本能用，但更适合轻量问答**  
   `ai shell` 中直接输入自然语言会走 `IntentParser` 的关键词路由。对生产闭环，推荐显式使用 `/plan`、`/run`、`/repair`、`/explain`；遇到较长输出时，CLI 会按行 flush 当前阶段说明与响应正文，HTTP 侧则可改用 `command/stream` 接口接收阶段事件。

### 6. 在 shell 外完成同一个闭环

如果你不想进交互 shell，也可以用同一个 session 串起来：

```bash
./bin/consilens-cli.sh ai plan --session orders-loop "compare orders ..."
./bin/consilens-cli.sh ai explain --session orders-loop -c orders-loop.yaml
./bin/consilens-cli.sh ai run --session orders-loop --approve-execute
./bin/consilens-cli.sh ai repair --session orders-loop -o orders-loop-repaired.yaml
./bin/consilens-cli.sh ai run --session orders-loop --approve-execute
./bin/consilens-cli.sh ai memories --session orders-loop
```

### 7. 什么时候需要 `ai diagnose --result ...`

如果你一直在 `ai run` / `ai shell` 这个 session runtime 内闭环，运行阶段会直接生成 diagnosis artifact，通常**不需要再手工指定结果文件**。

只有在下面两种场景才需要：

1. 你要分析 **session 外部** 的 diff 结果文件
2. 你要对已有 diff evidence 重新指定 analyzer / 输出路径

这时要求输入文件必须包含**行级差异证据**：

```bash
./bin/consilens-cli.sh ai diagnose \
  --session orders-loop \
  --result ./diff-records.json \
  --analyzer rulebased \
  -o diagnose.md
```

如果结果来自普通 `consilens diff`，配置里必须包含 `json + diff-record` sink，例如：

```yaml
result:
  sinks:
    - format: console
      type: result
    - format: json
      type: diff-record
      properties:
        path: ./diff-records.json
        pretty: true
```

### 8. 面向运维/值班的最简步骤

如果你不想看完整 chat 说明，只想最快完成一轮“执行 → 判断 → 修复 → 复验”，可以直接按下面顺序操作：

```bash
# 1) 检查插件和后端装配
./bin/consilens-cli.sh ai doctor

# 2) 创建或更新 session 配置
./bin/consilens-cli.sh ai plan --session orders-loop "compare orders ..." --dry-run

# 3) 执行真实 diff，并自动得到 diagnosis
./bin/consilens-cli.sh ai run --session orders-loop --approve-execute

# 4) 基于 diagnosis 重新生成配置
./bin/consilens-cli.sh ai repair --session orders-loop -o orders-loop-repaired.yaml

# 5) 用修复后的当前配置再跑一轮
./bin/consilens-cli.sh ai run --session orders-loop --approve-execute

# 6) 需要追历史时查看 session memories
./bin/consilens-cli.sh ai memories --session orders-loop
```

如果需要人工交互排查，再进入：

```bash
./bin/consilens-cli.sh ai --session orders-loop
```

### 9. 最小可运行 demo

下面这组命令适合第一次把 session 闭环跑通。它不要求你先手写 YAML，而是直接让 runtime 生成、执行、修复、复验：

```bash
export MYSQL_USER=demo_user
export MYSQL_PASSWORD=demo_pass
export PG_USER=demo_user
export PG_PASSWORD=demo_pass

./bin/consilens-cli.sh ai plan \
  --session demo-orders \
  "compare mysql orders with postgresql orders by order_id" \
  --no-llm \
  --source-type mysql \
  --source-url jdbc:mysql://127.0.0.1:3306/shop \
  --source-table orders \
  --source-user-env MYSQL_USER \
  --source-password-env MYSQL_PASSWORD \
  --target-type postgresql \
  --target-url jdbc:postgresql://127.0.0.1:5432/shop \
  --target-table orders \
  --target-user-env PG_USER \
  --target-password-env PG_PASSWORD \
  --keys order_id \
  --fields status,amount,updated_at \
  --dry-run \
  -o demo-orders.yaml

./bin/consilens-cli.sh ai run --session demo-orders --approve-execute
./bin/consilens-cli.sh ai repair --session demo-orders -o demo-orders-repaired.yaml
./bin/consilens-cli.sh ai run --session demo-orders --approve-execute
./bin/consilens-cli.sh ai memories --session demo-orders
```

如果第二次 `ai run` 的 diagnosis 明显收敛，说明这条最小闭环已经走通。接下来再进入：

```bash
./bin/consilens-cli.sh ai --session demo-orders
```

同样的流程也可以直接执行脚本：

```bash
bash examples/ai-chat-closed-loop-demo.sh
```

### 10. 外部 `diff-record` 诊断 demo

如果 diff 不是由 `ai run` 产生，而是来自普通 `consilens diff` 或历史产物，可以单独把 evidence 接进当前 session：

```bash
./bin/consilens-cli.sh diff -c demo-orders.yaml

./bin/consilens-cli.sh ai diagnose \
  --session demo-orders \
  --result ./diff-records.json \
  --analyzer rulebased \
  -o demo-diagnose.md

./bin/consilens-cli.sh ai repair --session demo-orders -o demo-orders-repaired.yaml
./bin/consilens-cli.sh ai run --session demo-orders --approve-execute
```

前提是 `demo-orders.yaml` 里包含：

```yaml
result:
  sinks:
    - format: console
      type: result
    - format: json
      type: diff-record
      properties:
        path: ./diff-records.json
        pretty: true
```

也可以直接执行脚本版本：

```bash
bash examples/ai-external-diff-record-demo.sh
```

## 配置文件格式

### 最小配置示例

```yaml
source:
  type: mysql
  connection:
    url: jdbc:mysql://localhost:3306/source_db
    username: user1
    password: password1
  resource:
    type: table
    name: orders

target:
  type: postgresql
  connection:
    url: jdbc:postgresql://localhost:5432/target_db?currentSchema=public
    username: user2
    password: password2
  resource:
    type: table
    name: orders

comparison:
  keys:
    source:
      - order_id
    target:
      - order_id

strategy:
  mode: checksum
  algorithm: xor

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

### 完整配置说明

```yaml
source:
  type: mysql
  connection:
    url: jdbc:mysql://localhost:3306/database1?useSSL=false&serverTimezone=UTC
    username: user1
    password: password1
  resource:
    type: table
    name: users

target:
  type: postgresql
  connection:
    url: jdbc:postgresql://localhost:5432/database2?currentSchema=public&ssl=false&ApplicationName=consilens
    username: user2
    password: password2
  resource:
    type: sql
    path: |
      SELECT id, email, name, phone, created_at, updated_at
      FROM customers
      WHERE status = 'active'

comparison:
  keys:
    source:
      - id
      - email
    target:
      - id
      - email
  fields:
    source:
      - name
      - phone
      - created_at
    target:
      - name
      - phone
      - created_at
  extraColumns:
    - updated_at
  filters:
    source: "status = 'active'"
    target: "status = 'active'"

strategy:
  mode: checksum
  algorithm: xor
  bisectionFactor: 4
  bisectionThreshold: 10000
  batchSize: 1000
  enableProfiling: false
  localCompare:
    mode: full

# ─── 并发配置 ────────────────────────────────────────────
concurrency:
  io:                    # 用于数据库 I/O 的线程池
    core: 8
    max: 32
    queueSize: 10000
    keepAliveSeconds: 60
    threadNamePrefix: consilens-io-
  cpu:                   # 用于哈希计算的 CPU 线程池
    core: 4
    max: 8
    queueSize: 10000
    keepAliveSeconds: 60
    threadNamePrefix: consilens-cpu-

# ─── 结果输出（result.sinks）────────────────────────────
# 可配置多个 sink，每个 sink 独立处理输出
result:
  sinks:
    # ── 控制台摘要输出 ──
    - format: console
      type: result

    # ── 输出差异行到 JSON 文件 ──
    - format: json
      type: diff-record
      properties:
        path: ./diff_results.json   # 固定路径
        pretty: true

    # ── 输出差异行到 CSV 文件 ──
    # - format: csv
    #   type: diff-record
    #   properties:
    #     path: ./diff_results_${taskId}.csv   # 输出文件路径，支持 ${变量名} 占位符
    #     delimiter: ","                         # 列分隔符
    #     includeHeader: true                    # 是否输出表头

    # ── 输出差异行到数据库表 ──
    # - format: table
    #   type: diff-record
    #   properties:
    #     url: jdbc:mysql://localhost:3306/results?useSSL=false&serverTimezone=UTC
    #     username: consilens_user
    #     password: consilens_pass
    #     driver: com.mysql.cj.jdbc.Driver
    #     maxPoolSize: 10
    #     prefix: diff_results_       # 表名前缀
    #     suffixTimestamp: true        # 追加时间戳后缀
    #     createTable: true            # 不存在时自动建表
    #     dropIfExists: false          # 是否先删除同名表
    #     defaultColumnLength: 500     # VARCHAR 列默认长度
    #     batchSize: 1000              # 批量插入大小
```

## 策略选择指南

| 场景 | 推荐策略 |
|------|----------|
| 两库在同一实例，或网络极快 | `join` |
| 跨数据库、跨机房 | `checksum` |
| 超大表（> 千万行）跨库 | `checksum` + `algorithm: xor` |

### `checksum` 策略

通过递归二分法将表分段，每段独立计算校验和后比较，网络传输量极小。支持任意数据库组合。

推荐组合：

- 默认优先 `algorithm: xor`
- 终局小段默认使用 `localCompare.mode: full`
- 只有明确配置 `localCompare.mode: row-hash` 时，才会先拉主键 + 行哈希再回查差异行

### `join` 策略

在数据库端直接执行 FULL OUTER JOIN，结果直接返回差异行，速度最快，但要求两侧库可互访（如同实例不同 schema）。

## 数据标准化

对跨数据库比对（如 MySQL vs PostgreSQL）中常见的类型差异，Consilens 内置自动标准化逻辑，例如：

- 布尔值（`TINYINT(1)` vs `BOOLEAN`）
- 数字精度（浮点截断）
- 日期时间格式（时区对齐）
- 字符串前后空格

如需自定义，可在配置中添加 `normalization` 节，详见[开发文档](../docs/02-配置详解.md)。

## 性能调优

1. **并发池**：`concurrency.io` 控制数据库查询并发，`concurrency.cpu` 控制哈希计算并发，根据机器核数和网络延迟调整
2. **bisectionThreshold**：阈值越小分段越细，并发度越高；但分段过多时协调开销也会上升，建议 5000–20000
3. **batchSize**：单次 SQL 查询返回行数，内存较大时可适当调高（如 5000）
4. **JVM 参数**：大数据量建议加大堆内存，如 `-Xmx8g -Xms8g`

## 故障排除

**连接失败**：检查 URL、用户名、密码，以及数据库服务是否启动、防火墙是否放行

**配置验证失败**：使用 `config validate --verbose` 查看详细错误；注意两侧 `comparison.keys.source`/`comparison.keys.target` 数量必须相等，`strategy.mode` 必须是 `checksum` 或 `join`，`strategy.algorithm` 必须是 `concat` 或 `xor`

**内存不足**：调小 `batchSize` 或 `bisectionThreshold`，并加大 JVM 堆内存

**结果有差异但不确定原因**：加 `--verbose` 开启详细日志，检查两侧数据类型映射是否需要标准化配置
