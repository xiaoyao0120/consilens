-- ClickHouse seed data for Consilens examples.
-- ClickHouse 23+ compatible
-- Target row count: 10000 rows in each table used by the examples.
--
-- Usage:
--   clickhouse-client --host localhost --port 9000 --user default \
//     --password "$CLICKHOUSE_PASSWORD" < examples/seed-sql/load-clickhouse-consilens-demo-data.sql
--
-- Note: ClickHouse syntax differences:
--   - Uses MergeTree engine
--   - No BOOLEAN type, use UInt8
--   - ENUM types are native
--   - JSON stored as String or JSON object (ClickHouse 24+)
--   - generateSeries for sequence generation
--   - toDateTime/toDate for date conversion

-- =========================================================
-- Create database
-- =========================================================

CREATE DATABASE IF NOT EXISTS consilens_demo;
CREATE DATABASE IF NOT EXISTS mydb;

-- =========================================================
-- sequence helper using generateSeries
-- =========================================================

CREATE TABLE IF NOT EXISTS consilens_demo.consilens_digits (
    d UInt8
) ENGINE = Memory;

INSERT INTO consilens_demo.consilens_digits VALUES
    (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

CREATE TABLE IF NOT EXISTS consilens_demo.consilens_seq (
    n UInt32
) ENGINE = Memory;

INSERT INTO consilens_demo.consilens_seq
SELECT
    ones.d
        + tens.d * 10
        + hundreds.d * 100
        + thousands.d * 1000
        + 1 AS n
FROM consilens_demo.consilens_digits ones
CROSS JOIN consilens_demo.consilens_digits tens
CROSS JOIN consilens_demo.consilens_digits hundreds
CROSS JOIN consilens_demo.consilens_digits thousands
WHERE
    ones.d
        + tens.d * 10
        + hundreds.d * 100
        + thousands.d * 1000 < 10000
ORDER BY n;

-- =========================================================
-- consilens_performance_demo_table
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.consilens_performance_demo_table;

CREATE TABLE consilens_demo.consilens_performance_demo_table (
    record_id String,
    col_tinyint Int8,
    col_smallint Int16,
    col_mediumint Int32,
    col_int Int32,
    col_bigint Int64,
    col_unsigned_int UInt64,
    col_float Float32,
    col_double Float64,
    col_decimal Decimal(18,4),
    col_numeric Decimal(18,4),
    col_char FixedString(10),
    col_varchar_50 String,
    col_varchar_100 String,
    col_varchar_255 String,
    col_text String,
    col_mediumtext String,
    col_binary FixedString(8),
    col_varbinary String,
    col_blob String,
    col_date Date,
    col_datetime DateTime,
    col_timestamp DateTime,
    col_time String,
    col_boolean UInt8,
    col_tinyint_bool UInt8,
    col_enum Enum8('new' = 1, 'processing' = 2, 'done' = 3, 'failed' = 4),
    col_set String,
    col_json String,
    user_name String,
    email String,
    phone String,
    address String,
    city String,
    country String,
    postal_code String,
    amount Decimal(18,4),
    balance Decimal(18,4),
    credit_limit Decimal(18,4),
    status String,
    category String,
    priority Int16,
    score Float64,
    created_at DateTime,
    updated_at DateTime,
    deleted UInt8 DEFAULT 0,
    dt Date
) ENGINE = MergeTree()
ORDER BY record_id
PARTITION BY toYYYYMM(dt);

INSERT INTO consilens_demo.consilens_performance_demo_table (
    record_id,
    col_tinyint,
    col_smallint,
    col_mediumint,
    col_int,
    col_bigint,
    col_unsigned_int,
    col_float,
    col_double,
    col_decimal,
    col_numeric,
    col_char,
    col_varchar_50,
    col_varchar_100,
    col_varchar_255,
    col_text,
    col_mediumtext,
    col_binary,
    col_varbinary,
    col_blob,
    col_date,
    col_datetime,
    col_timestamp,
    col_time,
    col_boolean,
    col_tinyint_bool,
    col_enum,
    col_set,
    col_json,
    user_name,
    email,
    phone,
    address,
    city,
    country,
    postal_code,
    amount,
    balance,
    credit_limit,
    status,
    category,
    priority,
    score,
    created_at,
    updated_at,
    deleted,
    dt
)
SELECT
    concat('REC', lpad(toString(n), 10, '0')) AS record_id,
    toInt8(modulo(n, 100)) AS col_tinyint,
    toInt16(modulo(n, 30000)) AS col_smallint,
    toInt32(n * 3) AS col_mediumint,
    toInt32(n * 10) AS col_int,
    toInt64(n) * 1000003 AS col_bigint,
    toUInt64(2147483648 + n) AS col_unsigned_int,
    toFloat32(modulo(n, 1000) + 0.125) AS col_float,
    toFloat64(modulo(n, 100000) + 0.25) AS col_double,
    toDecimal64(ROUND(modulo(n, 100000) / 100.0 + 0.1234, 4), 4) AS col_decimal,
    toDecimal64(ROUND(modulo(n, 100000) / 50.0 + 0.5678, 4), 4) AS col_numeric,
    concat('C', lpad(toString(n), 9, '0')) AS col_char,
    concat('v50_', lpad(toString(n), 5, '0')) AS col_varchar_50,
    concat('v100_', lpad(toString(n), 5, '0'), '_stable') AS col_varchar_100,
    concat('v255_', lpad(toString(n), 5, '0'), '_stable_payload') AS col_varchar_255,
    concat('text-', lpad(toString(n), 5, '0')) AS col_text,
    concat('mediumtext-', lpad(toString(n), 5, '0'), '-', repeat('x', modulo(n, 32))) AS col_mediumtext,
    reinterpretAsString(toUInt64(n)) AS col_binary,
    reinterpretAsString(toUInt64(n * 17)) AS col_varbinary,
    reinterpretAsString(toUInt64(n * 31)) AS col_blob,
    toDate('2026-05-01') + modulo(n, 7) AS col_date,
    toDateTime('2026-05-01 00:00:00') + modulo(n, 10000) AS col_datetime,
    toDateTime('2026-05-01 00:00:00') + modulo(n, 10000) AS col_timestamp,
    concat(
        lpad(toString(modulo(n, 24)), 2, '0'), ':',
        lpad(toString(modulo(n, 60)), 2, '0'), ':',
        lpad(toString(modulo(n, 60)), 2, '0')
    ) AS col_time,
    if(modulo(n, 2) = 0, 1, 0) AS col_boolean,
    modulo(n, 2) AS col_tinyint_bool,
    CASE modulo(n, 4)
        WHEN 0 THEN 'new'
        WHEN 1 THEN 'processing'
        WHEN 2 THEN 'done'
        ELSE 'failed'
    END AS col_enum,
    CASE modulo(n, 3)
        WHEN 0 THEN 'a,b'
        WHEN 1 THEN 'b'
        ELSE 'c'
    END AS col_set,
    concat('{"value":"json_', lpad(toString(n), 5, '0'), '"}') AS col_json,
    concat('user_', lpad(toString(n), 5, '0')) AS user_name,
    concat('user_', lpad(toString(n), 5, '0'), '@example.com') AS email,
    concat('+861380', lpad(toString(n), 6, '0')) AS phone,
    concat('No.', toString(n), ' Consilens Road') AS address,
    CASE modulo(n, 4)
        WHEN 0 THEN 'Shanghai'
        WHEN 1 THEN 'Beijing'
        WHEN 2 THEN 'Shenzhen'
        ELSE 'Hangzhou'
    END AS city,
    'CN' AS country,
    lpad(toString(modulo(n, 1000000)), 6, '0') AS postal_code,
    toDecimal64(ROUND(10 + modulo(n, 5000) / 10.0, 4), 4) AS amount,
    toDecimal64(ROUND(1000 + modulo(n, 8000) / 10.0, 4), 4) AS balance,
    toDecimal64(ROUND(5000 + modulo(n, 3000) / 10.0, 4), 4) AS credit_limit,
    CASE modulo(n, 4)
        WHEN 0 THEN 'active'
        WHEN 1 THEN 'inactive'
        WHEN 2 THEN 'pending'
        ELSE 'blocked'
    END AS status,
    CASE modulo(n, 5)
        WHEN 0 THEN 'retail'
        WHEN 1 THEN 'finance'
        WHEN 2 THEN 'logistics'
        WHEN 3 THEN 'manufacturing'
        ELSE 'public'
    END AS category,
    toInt16(modulo(n, 5) + 1) AS priority,
    toFloat64(modulo(n, 100) + 0.5) AS score,
    toDateTime('2026-05-01 00:00:00') + modulo(n, 10000) AS created_at,
    toDateTime('2026-05-01 00:05:00') + modulo(n, 10000) AS updated_at,
    if(modulo(n, 10) = 0, 1, 0) AS deleted,
    toDate('2026-05-01') AS dt
FROM consilens_demo.consilens_seq;

-- =========================================================
-- validation queries
-- =========================================================

SELECT 'clickhouse.consilens_demo.consilens_performance_demo_table' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       min(record_id) AS min_record_id,
       max(record_id) AS max_record_id,
       round(sum(amount), 4) AS amount_sum
FROM consilens_demo.consilens_performance_demo_table;

-- =========================================================
-- cleanup helper tables
-- =========================================================

DROP TABLE IF EXISTS consilens_demo.consilens_digits;
DROP TABLE IF EXISTS consilens_demo.consilens_seq;
