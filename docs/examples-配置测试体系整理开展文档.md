# Consilens 数据比对引擎测试体系开展文档

> 定位：这是**从零设计**的测试体系方案，不是对现有 examples 的增量修补。目标是让每次代码改动、每次发版前，都能跑一套可信的回归：配置字段全覆盖、数据源全覆盖、差异场景全覆盖、字段类型全覆盖、完全一致场景全覆盖。
>
> 本文档只描述方案与验收口径；具体实施会在确认后按"事实源 → 配置矩阵 → 种子 → 回归脚本 → 断言"顺序落地。

## 1. 体系目标与验收口径

| 目标 | 含义 | 验收口径（可机械判定） |
| --- | --- | --- |
| 配置全覆盖 | 配置模型的每个字段、每个枚举取值、每个字段类型（string/int/long/boolean/array/object/map/null）都至少被一个配置用到 | 覆盖矩阵测试逐字段断言，缺任一字段即失败 |
| 能力全覆盖 | 引擎支持的每种比对能力都有独立用例：自定义 SQL、聚合比对、checksum 递归、join、映射、排除、并发大表、同库自比等 | 每种能力至少出现在一个可执行配置中，且对应基线断言存在（见第 3 章） |
| 数据源全覆盖 | 每个受支持的数据库至少作为"参与方"跑通一次真实比对；当前所有目标端都接 MySQL，同库自比可覆盖其余参与方式 | 11 种数据源（mysql、postgresql、clickhouse、tidb、starrocks、doris、trino、presto、oceanbase、oracle、sqlserver）全部出现在可执行用例中 |
| 差异场景全覆盖 | 三类差异都出现在种子与断言里：SOURCE_MISSING（目标多）、TARGET_MISSING（目标少）、MISMATCH（值不同） | 每个 pair 的基线断言包含三类计数；同库自比也含差异用例 |
| 字段类型全覆盖 | 引擎逻辑类型体系中的每个类型族都出现在同一张全类型表里，两端各一次 | 全类型表 DDL 按 TypeFactory/DataType 枚举校验，缺类型即失败 |
| 完全一致覆盖 | 存在"预期零差异"的对照组，防止回归成"永远报差异"或"差异统计恒为 0" | 完全一致用例断言 total=0；差异用例断言精确计数，两类互相钳制 |
| 可信回归 | 每次改动后跑同一套命令即可得到通过/失败结论，无人工判断 | 脚本退出码即结果；所有断言有基线文件与 JUnit 双层守护 |

## 2. 从配置模型推导覆盖清单（事实源）

覆盖矩阵不能拍脑袋，必须从模型代码反推。以下清单已从源码核实：

### 2.1 连接与资源

- `source/target.type`：11 种数据源类型（见 4.1）
- `source/target.name`
- `source/target.connection.url/username/password`（`jdbcUrl` 别名需覆盖）
- `source/target.connection.*`：`@JsonAnySetter` 任意扩展属性（至少覆盖一项）
- `source/target.resource.type`：`table`、`sql`（仅这两种，`sql` 必须是 SELECT/WITH 开头且无分号注释）
- `source/target.resource.name`（table 必填）
- `source/target.resource.path`（sql 必填）
- `source/target.readOptions`：`Map<String,Object>` 任意键（consistency/batchSize/fetchSize/useCursorFetch）

### 2.2 比对配置

- `comparison.keys.source/target`：单键、多键（复合键）、两侧大小不一致（负例，期望校验失败）
- `comparison.fields.source/target`：显式子集；两侧数量不一致（负例）
- `comparison.exclude.source/target`
- `comparison.mappings`：name + column / expression / literal 三种来源，`key: true`、`compare: false`；column/expression/literal 配置冲突（负例）；fields 与 mappings 同时配置（负例）
- `comparison.extraColumns`
- `comparison.filters.source/target`：只配一侧（负例）

### 2.3 策略

- `strategy.mode`：`checksum`、`join`（`local` 已禁用，负例）
- `strategy.algorithm`：`concat`、`xor`
- `strategy.bisectionFactor/bisectionThreshold/batchSize/enableProfiling/maxDifferences`
- `strategy.localCompare.mode`：`full`、`row-hash`

### 2.4 并发与规范化

- `concurrency.io/cpu`：core/max/queueSize/keepAliveSeconds/threadNamePrefix
- `normalization.global/source/target`：类型键（decimal/float/double/date/time/datetime/timestamp/binary/boolean/varchar/char/numeric/int/bigint 等）+ 规则参数（precision/rounding/format/timezone/comparisonMode/encoding/uppercase/trueValue/falseValue/nullValue）

### 2.5 结果输出

- `result.failOnSinkError`：true/false
- `result.sinks`：format ∈ {console, csv, json, table}，type ∈ {result, diff-record}，enabled ∈ {true, false}
- table sink：`properties.type`（当前校验仅允许 mysql/postgresql）、url/username/password/driver/maxPoolSize/tableName/prefix/suffixTimestamp/createTable/dropIfExists/defaultColumnLength/batchSize/columns(name/defaultValue/value/columnType)
- diff-record sink：`mergeDefaults`、columns 同 table sink
- 负例：table sink 类型不是 mysql/postgresql 应被校验拒绝

### 2.6 配置格式与占位符

- YAML 与 JSON 两种格式加载同一份语义
- `${env.X}` 环境占位符：有值可解析；缺值必须报错（负例）
- 配置加载失败、校验失败要有明确错误信息（负例断言）

## 3. 能力全覆盖矩阵

比对能力是独立于配置字段、数据源、差异类型的覆盖维度。每种能力必须有一个"以该能力为主"的用例，并且该用例要有自己的基线断言，防止能力被别的用例顺带覆盖而失去针对性。

### 3.1 能力清单（每对数据源必须全有）

| 能力 | 配置要点 | 基线断言 |
| --- | --- | --- |
| 自定义 SQL | 两端 `resource.type=sql`，`path` 为 SELECT/WITH 查询（可带过滤、JOIN、表达式列），覆盖"同构 SQL"与"异构 SQL 归一" | 与全类型表相同的差异数（01） |
| 聚合比对 | 明细表经自定义 SQL 聚合（GROUP BY 日期/状态 + COUNT/SUM/MAX）后，与目标端预聚合表 `daily_order_summary` 比对，复合键 + filters 圈定区间 | totalMin 下限 + 分项上限（02） |
| checksum 递归 | `strategy.mode=checksum` + `algorithm=xor/concat`，二分参数（factor/threshold）触达"大段递归 → 小段全行"路径 | 精确差异计数（01） |
| join 全表比对 | `strategy.mode=join`，source/target 同库或同实例，覆盖整表逐行比对路径 | 精确差异计数（05/同库 join） |
| 字段映射 | `comparison.mappings`：column/expression/literal 三种来源、`key:true`、`compare:false`（04） | users 表 email MISMATCH=1 |
| 字段排除 | `comparison.exclude.source/target`，全字段默认比对下排除部分列（07） | 排除后差异数按设计值 |
| 大表并发 | 10000+ 行 + `concurrency.io/cpu` 双池 + `batchSize` 分片，验证分片与并发路径（03） | 与全类型表相同差异数 |
| 同库自比 | source/target 指向同一 JDBC URL，join 模式验证"迁移前后/备份表"场景 | orders/orders_backup 差异数（05） |
| 完全一致对照 | 两端种子完全一致，checksum 与 join 各一份，断言 total=0 | total=0（11） |
| 分片/分区过滤 | `comparison.filters` 指定分区键区间，避免全表扫描（Doris 分区表） | 分区内差异数（10，Doris） |
| 性能全字段 | 200 万行 + 50 列全字段 + 双 result sink（06，独立开关） | 行数 + 差异数合理范围 |
| 输出方言 | result sink 落 PostgreSQL/MySQL 方言列类型（`BIGINT/TIMESTAMP/INTEGER`），diff-record `mergeDefaults`（08） | CLI 退出码 + result 表行数 |

能力与配置字段、数据源是两个正交维度：能力矩阵保证"每个能力都单独可验证"，数据源矩阵保证"每个能力至少在一种真实数据库上跑过"，交集由每对目录的 01-11 场景文件体现。

### 3.2 现有能力用例与缺口

已核实 examples 现状：

- 01 自定义 SQL（两端 sql 资源）、02 聚合（明细聚合 vs 预聚合表）、03 大表并发、04 映射、05 同库 join 已在 10 个 pair 目录统一；
- 缺失：exclude、完全一致对照、输出方言、JSON 格式、分区过滤、性能全字段未进入对目录（仅顶层散落或不存在）。

## 4. 数据源全覆盖矩阵

### 4.1 参与方清单（11 种）

插件目录已核实：mysql、postgresql、clickhouse、tidb、starrocks、doris、trino、presto、oceanbase、oracle、sqlserver（`consilens-connector-plugins/` 下 11 个 provider）。

### 4.2 覆盖方式：两套矩阵

每类矩阵内部保持同一套表结构与种子差异口径，差异只在方言与 JDBC 连接。

#### A. 跨库矩阵（MySQL → 目标端）

| pair | 目标方言 | 当前可复用基础 |
| --- | --- | --- |
| mysql-pg | postgresql | 有完整性能表种子 |
| mysql-clickhouse | clickhouse | 有完整性能表种子 |
| mysql-tidb | tidb | 有全类型表（MySQL 兼容） |
| mysql-starrocks | starrocks | 有全类型表 |
| mysql-doris | doris | 有全类型表 |
| mysql-trino | trino（目标表落 MySQL 13306） | 有全类型表 |
| mysql-presto | presto（目标表落 MySQL 13306） | 有全类型表 |
| mysql-oceanbase | oceanbase | 有全类型表 |
| mysql-oracle | oracle | 有全类型表（含 BLOB/CLOB/RAW/BINARY_FLOAT） |
| mysql-sqlserver | sqlserver | 有全类型表（含 NVARCHAR/VARBINARY/BIT） |

#### B. 同库矩阵（每库内部自己比）

| 数据源 | 形态 |
| --- | --- |
| mysql | orders vs orders_backup（join + normalization 全参数） |
| postgresql | 全类型表自比（完全一致）+ 差异版自比 |
| oracle | 全类型表自比 + 差异版自比 |
| sqlserver | 全类型表自比 + 差异版自比 |
| clickhouse | 同实例两表自比 |
| tidb/starrocks/doris/oceanbase | 同实例自比（MySQL 兼容方言） |
| trino/presto | 同一 catalog 下自比（如可能） |

说明：同库矩阵满足"每种数据源至少真实跑一次"；trino/presto 若引擎只支持 JDBC 直连且本地环境不便起节点，可用 `--dry-run` 或容器集成测试兜底，但必须显式记录该豁免，不能静默缺失。

### 4.3 端口约定（回归脚本与配置统一）

MySQL 13306、PostgreSQL 5432、ClickHouse 9000(native)/8123(http)、TiDB 4000、StarRocks/Doris 9030、Oracle 1521、SQLServer 1433、Trino 8081、Presto 8085、OceanBase 2881。

## 5. 差异场景与种子设计

### 5.1 三类差异语义

- `SOURCE_MISSING`：目标端多出、源端没有（种子在目标端额外插入）
- `TARGET_MISSING`：目标端缺失、源端有（种子在目标端跳过某行）
- `MISMATCH`：同一主键两侧值不同（种子在目标端改值）

### 5.2 每 pair 种子统一口径（唯一事实源 = `load-mysql.sql`）

| 表 | 差异设计 | 场景归属 |
| --- | --- | --- |
| consilens_performance_demo_table | 主键 `REC0000000001` amount 改 99999.9999（MISMATCH）；`REC0000000002` status 改 modified_status（MISMATCH）；目标端多 `REC_EXTRA_001`（SOURCE_MISSING）；目标端缺 `REC0000000005`（TARGET_MISSING，跳过 n=5） | 全类型比对 |
| users | 目标端 email 改 modified@example.com（MISMATCH） | 映射比对 |
| orders / orders_backup | 备份表 amount 改 99999.9999（MISMATCH）；目标端多一条订单 10001（SOURCE_MISSING） | 同库 join |

数据量：基础矩阵 10000 行；性能/大表场景 200 万行独立开关（见 6）。

### 5.3 完全一致对照组

每个数据源至少有一份"两端种子完全一致"的配置（如 `xx-identical.yaml`），断言 total=0、sourceMissing=0、targetMissing=0、mismatch=0。它和差异用例互为反向验证：

- 若实现"把所有行都算差异"，一致用例失败；
- 若实现"永远零差异"，差异用例失败。

## 6. 字段类型全覆盖设计

### 6.1 类型事实源

引擎逻辑类型由两套枚举共同决定：

- `consilens-connector-api` 的 `DataType`：TINYINT/SMALLINT/INTEGER/BIGINT/DECIMAL/NUMERIC/FLOAT/DOUBLE/REAL/CHAR/VARCHAR/TEXT/CLOB/LONGVARCHAR/DATE/TIME/TIMESTAMP/DATETIME/TIMESTAMP_WITH_TIMEZONE/TIME_WITH_TIME_ZONE/BOOLEAN/BIT/BINARY/VARBINARY/BLOB/LONGBLOB/JSON/JSONB/UUID/ARRAY/OBJECT/UNKNOWN
- `consilens-common` 的 `DataType`：NULL/BOOLEAN/INTEGER/FLOAT/DOUBLE/DECIMAL/STRING/BINARY/DATE/TIME/TIMESTAMP/INTERVAL/ARRAY/MAP/STRUCT/JSON/XML/UUID/GEOMETRY/ENUM/OBJECT/UNKNOWN

### 6.2 全类型表列清单

`consilens_performance_demo_table` 应覆盖所有类型族：

| 族 | 列 |
| --- | --- |
| 整数 | col_tinyint、col_smallint、col_mediumint、col_int、col_bigint、col_unsigned_int |
| 小数/浮点 | col_float、col_double、col_decimal、col_numeric |
| 字符串 | col_char、col_varchar_50、col_varchar_100、col_varchar_255、col_text、col_mediumtext |
| 二进制 | col_binary、col_varbinary、col_blob |
| 时间 | col_date、col_datetime、col_timestamp、col_time |
| 布尔 | col_boolean、col_tinyint_bool |
| 特殊 | col_enum、col_set、col_json |
| 业务列 | user_name/email/phone/address/city/country/postal_code/amount/balance/credit_limit/status/category/priority/score/created_at/updated_at |

### 6.3 方言映射与差异处理

- mysql/tidb/starrocks/doris/oceanbase（MySQL 兼容）：原生 TINYINT/SMALLINT/MEDIUMINT/INT/BIGINT/UNSIGNED/FLOAT/DOUBLE/DECIMAL/CHAR/VARCHAR/TEXT/MEDIUMTEXT/BINARY/VARBINARY/BLOB/DATE/DATETIME/TIMESTAMP/TIME/BOOLEAN/ENUM/SET/JSON；StarRocks/Doris 用 `UNIQUE KEY`/`PRIMARY KEY` 才能 UPDATE。
- postgresql：SMALLINT/INTEGER/BIGINT/NUMERIC/DOUBLE PRECISION/TEXT/BYTEA/DATE/TIMESTAMP/TIME/BOOLEAN/JSONB。
- clickhouse：`Nullable(Int8)/Nullable(Int16)/Nullable(Int32)/Nullable(Int64)/Nullable(Float64)/Nullable(Decimal(18,4))/Nullable(String)/Nullable(DateTime64)/Nullable(Date)`。
- oracle：NUMBER(p,s)/VARCHAR2/CHAR/CLOB/BLOB/RAW/BINARY_FLOAT/BINARY_DOUBLE/TIMESTAMP/DATE，`col_time` 用 VARCHAR2(10)，时间比较需 `TO_DATE`。
- sqlserver：TINYINT/SMALLINT/INT/BIGINT/DECIMAL/NUMERIC/REAL/FLOAT/NVARCHAR/CHAR/NVARCHAR(MAX)/BINARY/VARBINARY/VARBINARY(MAX)/DATE/DATETIME/TIME/BIT。
- trino/presto：目标表实际落在 MySQL 13306（外部 catalog 映射），DDL 沿用 MySQL 类型。

两端生成逻辑必须用同一套确定性表达式（如 `n=1..10000` 的 MOD/CASE 组合），保证同语义值，只有方言语法不同；时间、二进制、JSON 用显式格式化（TO_DATE/HEXTORAW/CONCAT）避免隐式转换差异。

## 7. 回归执行体系

### 7.1 三层测试金字塔

| 层 | 内容 | 运行时机 | 依赖 |
| --- | --- | --- | --- |
| L0 单元/静态 | `ExampleCoverageMatrixTest`：配置可加载、字段/枚举/类型覆盖、种子一致性、基线同步 | 每次 `mvn test` | 无 |
| L1 契约 | `ExampleConfigurationCompatibilityTest`、`CrossDatabaseComparisonIntegrationTest`（目录结构 + `consilens.it.enabled=true` 时才连库） | 每次 `mvn test` | 无/可选数据库 |
| L2 真实比对 | `run-comparison-test.sh all`：灌种子 + 跑配置 + 基线断言 | 发版前 / CI | 11 种数据库运行中 |

### 7.2 回归脚本改造要求

1. 配置 glob 由 `0*.yaml` 扩为 `[0-9][0-9]-*.yaml`，覆盖 01-10 全部场景；
2. 每 pair 场景编号固定：01 全类型 checksum、02 聚合、03 大表并发、04 映射、05 同库 join、06 性能全字段（200 万行）、07 exclude、08 输出方言 sink、09 JSON 格式、10 分区过滤（Doris）、11 identical 完全一致；
3. 新增 `CONSILENS_RUN_PERFORMANCE=0|1`：默认跳过 06 全量；性能开关开启时才跑，且 06 的断言只做"行数 + 差异数合理"宽松校验；
4. 新增同库矩阵目录（如 `same-db/`）并纳入脚本，避免同库用例游离在回归外；
5. 新增 `--dry-run` 冒烟模式：无数据库环境也能验证所有配置"能加载、能通过校验、能构造 CompareRequest"。

### 7.3 基线文件

`test-baselines.json` 按"相对路径 → {total, sourceMissing, targetMissing, mismatch}"组织：

- 差异用例：精确计数（如 01/03 total=4、sourceMissing=1、targetMissing=1、mismatch=2）；
- 完全一致用例：total=0（精确）；
- 聚合/性能用例：totalMin 下限 + 分项上限；
- 新增配置必须同步登记基线，否则 JUnit 的"基线同步"测试失败。

### 7.4 断言双保险

- 脚本断言：CLI 输出文本解析 `Source missing rows / Target missing rows / Mismatched rows / Total differences`（`DiffCommand` 已输出这些行）；
- JUnit 断言：覆盖矩阵测试校验"配置清单 ↔ 种子 ↔ 基线 ↔ examples 文件"四者一致，防止只改了配置忘了改种子或基线。

## 8. 目录结构与实施顺序

### 8.1 目标目录结构

```text
examples/
├── cross-db/                        # 跨库矩阵：10 个 pair
│   ├── mysql-pg/{load-mysql.sql,load-postgresql.sql,01..11}
│   ├── mysql-oracle/{load-mysql.sql,load-oracle.sql,01..11}
│   └── ...（其余 8 个 pair 同构）
├── same-db/                         # 同库矩阵：每库至少 1 个 pair
│   ├── mysql/{seed.sql,01-identical.yaml,02-join-diff.yaml}
│   ├── postgresql/...
│   └── ...（oracle/sqlserver/clickhouse/tidb/starrocks/doris/oceanbase/trino/presto）
├── test-baselines.json
├── run-comparison-test.sh
└── README.md                        # 一页说清怎么跑、怎么加新数据源
```

现有顶层散落配置（`performance-test-*.yaml/json`、`mysql-to-*.yaml`、`configs/`、`seed-sql/`）不再作为事实源：可读入 `_archive/` 或删除，README 说明迁移去向，避免两套配置漂移。

### 8.2 实施顺序（每步带验收）

1. **冻结事实源**：`load-mysql.sql` 全类型表 DDL 与生成逻辑定稿 → 验收：DDL 覆盖 6.2 全列，生成逻辑确定性可重放；
2. **补齐方言目标端**：11 种数据源的全类型表种子 + 差异设计 → 验收：每端种子含 4.2 全部差异标记，方言类型映射通过 DDL 静态检查；
3. **建立配置矩阵**：cross-db 10 对 × 01-11 + same-db 每库用例 → 验收：矩阵测试新增断言全绿，配置全部可加载；
4. **改造回归脚本与基线**：glob、性能开关、同库矩阵、dry-run、基线登记 → 验收：脚本在无库环境能跑通 L0/L1；
5. **真实环境回归**：11 种库齐备时 `run-comparison-test.sh all` 全绿 → 验收：L2 断言与基线精确一致，identical 用例 total=0；
6. **接入 CI/发版门禁**：`mvn test`（L0/L1）+ 可选 L2 作为发布前哨兵。

## 9. 需要你确认的决策点

1. **同库矩阵范围**：11 种数据源全部起真实实例做 L2，还是核心库（mysql/pg/oracle/sqlserver）L2 + 其余容器化或豁免？（影响 CI 成本）
2. **旧文件处置**：顶层散落配置与 `configs/`、`seed-sql/` 删除还是归档到 `_archive/`？
3. **性能用例**：200 万行 06 场景默认关闭、CI 手动触发，是否可接受？
4. **基线严格度**：聚合/性能用例允许 totalMin 宽松断言，其余精确断言，是否可接受？
5. **新数据源接入流程**：是否要求"新增数据源 = 新增 pair 目录 + 种子 + 基线 + 矩阵测试项"四件套作为硬性规范？
