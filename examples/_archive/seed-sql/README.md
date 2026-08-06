# Consilens Demo Data Scripts

This directory contains repeatable seed scripts for the current Consilens example configurations.

## Files

### Performance Demo Data (10000 rows, full type coverage)

- `load-mysql-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table`, `mydb.users`, `mydb.orders`, `mydb.orders_backup`, and `production.fact_orders`.
  - Creates `diff_results` for table sink examples.
  - Inserts 10000 rows into every source table.
- `load-postgresql-consilens-demo-data.sql`
  - Creates `public.consilens_performance_demo_table`, `public.daily_order_summary`, and `public.users`.
  - Inserts 10000 rows into `consilens_demo.consilens_performance_demo_table` and `users`.
  - Builds `daily_order_summary` from the same aggregate logic used by `examples/detail-to-aggregate-custom-sql.yaml`.
- `load-oracle-consilens-demo-data.sql`
  - Creates `CONSILENS_PERFORMANCE_DEMO_TABLE`, `USERS`, `ORDERS`, and `ORDERS_BACKUP` in Oracle.
  - Inserts 10000 rows into every table.
  - Uses Oracle-specific syntax: VARCHAR2, NUMBER, CONNECT BY for sequence generation, HEXTORAW for binary data.
- `load-sqlserver-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table` in SQL Server.
  - Inserts 10000 rows with full type coverage.
  - Uses SQL Server-specific syntax: NVARCHAR, DATETIME2, BIT, IDENTITY.
- `load-clickhouse-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table` in ClickHouse.
  - Inserts 10000 rows with full type coverage.
  - Uses ClickHouse-specific syntax: MergeTree engine, Enum8, JSON.
- `load-tidb-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table` in TiDB.
  - Inserts 10000 rows (MySQL-compatible syntax).
- `load-doris-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table` for `examples/mysql-to-doris-partitioned-checksum.yaml`.
  - Inserts 10000 rows with the partition and comparison columns used by the Doris example.
- `load-starrocks-consilens-demo-data.sql`
  - Creates `consilens_demo.consilens_performance_demo_table` and `analytics.fact_orders`.
  - Inserts 10000 rows into every StarRocks target table.
- `load-trino-consilens-demo-data.sql`
  - Creates `memory.consilens_demo.consilens_performance_demo_table` in Trino (memory connector).
  - Inserts 10000 rows for query engine testing.
- `load-presto-consilens-demo-data.sql`
  - Creates `memory.consilens_demo.consilens_performance_demo_table` in Presto (memory connector).
  - Inserts 10000 rows for query engine testing.

## Covered Examples

- `examples/minimal-mysql-to-pg.yaml`
- `examples/same-db-mysql-comparison.yaml`
- `examples/same-db-oracle-comparison.yaml`
- `examples/performance-test-mysql-vs-postgres.yaml`
- `examples/performance-test-mysql-vs-postgres-exclude.yaml`
- `examples/performance-test-mysql-vs-postgres-output-postgres.yaml`
- `examples/performance-test-mysql-vs-postgres.json`
- `examples/custom-sql-mysql-vs-postgres-checksum.yaml`
- `examples/detail-to-aggregate-custom-sql.yaml`
- `examples/mysql-to-doris-partitioned-checksum.yaml`
- `examples/mysql-to-oracle-checksum.yaml`
- `examples/performance-test-mysql-vs-starrocks.yaml`
- `examples/large-table-mysql-to-starrocks.yaml`
- `examples/ai-chat-closed-loop-demo.sh`
- `examples/ai-external-diff-record-demo.sh`

The scripts also include `dt`, `deleted`, and result database setup needed by the partition/filter and sink scenarios already present in the examples. The row count is intentionally 10000 for repeatable local smoke coverage; the performance example comments still describe larger production-scale workloads.

## Run

MySQL:

```bash
mysql -h localhost -P 3306 -u "$MYSQL_USER" -p < examples/seed-sql/load-mysql-consilens-demo-data.sql
```

PostgreSQL for examples that point to the `postgres` database:

```bash
psql "postgresql://$PG_USER:$PG_PASSWORD@localhost:5432/postgres" \
  -f examples/seed-sql/load-postgresql-consilens-demo-data.sql
```

PostgreSQL for `examples/minimal-mysql-to-pg.yaml`, which points to `mydb`:

```bash
createdb -h localhost -U "$PG_USER" mydb
psql "postgresql://$PG_USER:$PG_PASSWORD@localhost:5432/mydb" \
  -f examples/seed-sql/load-postgresql-consilens-demo-data.sql
```

StarRocks:

```bash
mysql -h localhost -P 9030 -u "$STARROCKS_USER" -p < examples/seed-sql/load-starrocks-consilens-demo-data.sql
```

If StarRocks uses more than one replica by default, change `"replication_num" = "1"` in the script before running it.

Doris:

```bash
mysql -h localhost -P 9030 -u "$DORIS_USER" -p < examples/seed-sql/load-doris-consilens-demo-data.sql
```

Oracle:

```bash
sqlplus "$ORACLE_USER/$ORACLE_PASSWORD@//localhost:1521/ORCL" @ examples/seed-sql/load-oracle-consilens-demo-data.sql
```

SQL Server:

```bash
sqlcmd -S localhost,1433 -U "$SQLSERVER_USER" -P "$SQLSERVER_PASSWORD" \
  -i examples/seed-sql/load-sqlserver-consilens-demo-data.sql
```

ClickHouse:

```bash
clickhouse-client --host localhost --port 9000 --user "$CLICKHOUSE_USER" \
  --password "$CLICKHOUSE_PASSWORD" < examples/seed-sql/load-clickhouse-consilens-demo-data.sql
```

TiDB:

```bash
mysql -h localhost -P 4000 -u "$TIDB_USER" -p < examples/seed-sql/load-tidb-consilens-demo-data.sql
```

Trino:

```bash
trino --server localhost:8081 --catalog memory --schema default \
  --file examples/seed-sql/load-trino-consilens-demo-data.sql
```

Presto:

```bash
presto --server localhost:8085 --catalog memory --schema default \
  --file examples/seed-sql/load-presto-consilens-demo-data.sql
```

## Time Zone

For zero-difference cross-database checks that include timestamp fields, keep MySQL, PostgreSQL, and StarRocks comparison sessions on the same logical timezone. The seed scripts use Asia/Shanghai values. If the database servers use different defaults, configure the JDBC session or Consilens normalization before comparing timestamp columns.

## Expected Baseline

The default data is a zero-difference baseline for the matching example pairs:

- MySQL `consilens_demo.consilens_performance_demo_table` vs PostgreSQL `public.consilens_performance_demo_table`

## 刻意差异设计（回归基线）

每个 `mysql-*` 对目录的目标端 load 脚本都会制造固定数量的差异，用于验证 diff
结果能正确比对出来。统一的差异设计如下：

| 差异类型 | 位置 | 设计 |
| --- | --- | --- |
| MISMATCH | `consilens_performance_demo_table` | `REC0000000001.amount` 改为 `99999.9999`，`REC0000000002.status` 改为 `modified_status` |
| SOURCE_MISSING | `consilens_performance_demo_table` | 目标端多出 `REC_EXTRA_001` |
| TARGET_MISSING | `consilens_performance_demo_table` | 目标端跳过 `REC0000000005` |
| MISMATCH | `users` | `id=1` 的 email 改为 `modified@example.com` |
| MISMATCH | `orders_backup` | `order_id=1` 的 amount 改为 `99999.9999` |
| SOURCE_MISSING | `orders` | 目标端额外插入 `order_id=10001` |

`examples/test-baselines.json` 记录了每个场景配置（`0X-*.yaml`）应比对出的差异
数量。`examples/run-comparison-test.sh` 在每次 diff 后按该基线断言；`05-same-db-join`
与 `02-detail-to-aggregate` 使用 `totalMin`（至少应检出差异），其余场景使用精确
分项断言。

## 回归

```bash
# 构建 CLI
./mvnw -pl consilens-cli -am package -DskipTests

# 加载种子数据后运行全部对比测试（会按基线断言差异）
examples/run-comparison-test.sh all

# 单对数据库
examples/run-comparison-test.sh mysql-pg

# 跳过基线断言（只验证命令退出码）
CONSILENS_CHECK_BASELINE=0 examples/run-comparison-test.sh all
```

纯配置层面的回归（不依赖数据库）通过 JUnit 静态测试覆盖：

```bash
./mvnw -pl consilens-cli test -Dtest='ExampleConfigurationCompatibilityTest,ExampleCoverageMatrixTest'
```

该测试保证：examples 目录覆盖配置模型的全部字段、枚举取值、normalization 规则参数、
JSON 标量/数组/对象类型，且每个 `mysql-*` 对目录的 5 个场景配置、目标端 load 脚本
与 `test-baselines.json` 保持同步。
- MySQL `consilens_demo.consilens_performance_demo_table` vs Doris `consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs StarRocks `consilens_demo.consilens_performance_demo_table` for the integer fields in `performance-test-mysql-vs-starrocks.yaml`
- MySQL `production.fact_orders` vs StarRocks `analytics.fact_orders`
- MySQL `mydb.orders` vs `mydb.orders_backup`
- MySQL `mydb.users` vs PostgreSQL `public.users`
- MySQL `consilens_demo.consilens_performance_demo_table` vs Oracle `CONSILENS_PERFORMANCE_DEMO_TABLE`
- Oracle `ORDERS` vs Oracle `ORDERS_BACKUP`
- MySQL `consilens_demo.consilens_performance_demo_table` vs SQL Server `consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs ClickHouse `consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs TiDB `consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs Doris `consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs StarRocks `consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs Trino `memory.consilens_demo.consilens_performance_demo_table`
- MySQL `consilens_demo.consilens_performance_demo_table` vs Presto `memory.consilens_demo.consilens_performance_demo_table`

Each script ends with verification queries. Every main table should report `actual_rows = 10000` and `expected_rows = 10000`.

## Optional Difference Cases

Run these only after loading the baseline data.

Create a value mismatch in MySQL vs PostgreSQL performance data:

```sql
UPDATE public.consilens_performance_demo_table
SET amount = amount + 1
WHERE record_id = 'REC0000001000';
```

Create a target-only row in PostgreSQL:

```sql
INSERT INTO public.users (id, name, email, phone, status, created_at)
VALUES (10001, 'user_10001_extra', 'user_10001_extra@example.com', '+861380010001', 'active', '2026-01-01 00:00:00');
```

Create a MySQL same-database mismatch:

```sql
UPDATE mydb.orders_backup
SET status = 'manual_diff'
WHERE order_id = 1000;
```

Create a StarRocks target mismatch:

```sql
INSERT INTO analytics.fact_orders (
  order_id,
  customer_id,
  product_id,
  quantity,
  unit_price,
  total_amount,
  order_date,
  status,
  created_at,
  updated_at
)
VALUES (
  10001,
  100001,
  200001,
  1,
  9.0000,
  9.0000,
  '2026-05-01',
  'target_extra',
  '2026-05-01 00:00:00',
  '2026-05-01 00:10:00'
);
```
