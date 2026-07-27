-- Trino seed data for Consilens examples.
-- Trino 400+ compatible
-- Target row count: 10000 rows in each table used by the examples.
--
-- Usage:
--   trino --server localhost:8081 --catalog memory --schema default \
//     --file examples/seed-sql/load-trino-consilens-demo-data.sql
--
-- Note: Trino is a query engine, not a storage engine.
-- This script creates tables in the memory connector for testing.
-- For production, you would use Hive, Iceberg, or other connectors.
--
-- Trino syntax differences:
--   - Uses memory connector for testing
--   - No INSERT ... SELECT with CTE, use WITH + INSERT
--   - date/timestamp literals use DATE '...' / TIMESTAMP '...'
--   - No ENUM/SET type, use VARCHAR
--   - JSON stored as JSON type
--   - generate_series for sequence generation

-- =========================================================
-- Create schema
-- =========================================================

CREATE SCHEMA IF NOT EXISTS memory.consilens_demo;

-- =========================================================
-- sequence helper using generate_series
-- =========================================================

CREATE TABLE memory.consilens_demo.consilens_seq AS
SELECT n
FROM UNNEST(SEQUENCE(1, 10000)) AS t(n);

-- =========================================================
-- consilens_performance_demo_table
-- =========================================================

DROP TABLE IF EXISTS memory.consilens_demo.consilens_performance_demo_table;

CREATE TABLE memory.consilens_demo.consilens_performance_demo_table (
    record_id VARCHAR(16),
    col_tinyint TINYINT,
    col_smallint SMALLINT,
    col_mediumint INTEGER,
    col_int INTEGER,
    col_bigint BIGINT,
    col_unsigned_int BIGINT,
    col_float REAL,
    col_double DOUBLE,
    col_decimal DECIMAL(18,4),
    col_numeric DECIMAL(18,4),
    col_char CHAR(10),
    col_varchar_50 VARCHAR(50),
    col_varchar_100 VARCHAR(100),
    col_varchar_255 VARCHAR(255),
    col_text VARCHAR,
    col_mediumtext VARCHAR,
    col_binary VARBINARY,
    col_varbinary VARBINARY,
    col_blob VARBINARY,
    col_date DATE,
    col_datetime TIMESTAMP,
    col_timestamp TIMESTAMP,
    col_time TIME,
    col_boolean BOOLEAN,
    col_tinyint_bool TINYINT,
    col_enum VARCHAR(20),
    col_set VARCHAR(20),
    col_json JSON,
    user_name VARCHAR(64),
    email VARCHAR(128),
    phone VARCHAR(32),
    address VARCHAR(255),
    city VARCHAR(64),
    country VARCHAR(64),
    postal_code VARCHAR(20),
    amount DECIMAL(18,4),
    balance DECIMAL(18,4),
    credit_limit DECIMAL(18,4),
    status VARCHAR(20),
    category VARCHAR(30),
    priority SMALLINT,
    score DOUBLE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted TINYINT,
    dt DATE
);

INSERT INTO memory.consilens_demo.consilens_performance_demo_table (
    record_id, col_tinyint, col_smallint, col_mediumint, col_int, col_bigint,
    col_unsigned_int, col_float, col_double, col_decimal, col_numeric,
    col_char, col_varchar_50, col_varchar_100, col_varchar_255,
    col_text, col_mediumtext, col_binary, col_varbinary, col_blob,
    col_date, col_datetime, col_timestamp, col_time,
    col_boolean, col_tinyint_bool, col_enum, col_set, col_json,
    user_name, email, phone, address, city, country, postal_code,
    amount, balance, credit_limit, status, category, priority, score,
    created_at, updated_at, deleted, dt
)
WITH seq AS (
    SELECT n FROM UNNEST(SEQUENCE(1, 10000)) AS t(n)
)
SELECT
    CONCAT('REC', LPAD(CAST(n AS VARCHAR), 10, '0')) AS record_id,
    CAST(n % 100 AS TINYINT) AS col_tinyint,
    CAST(n % 30000 AS SMALLINT) AS col_smallint,
    CAST(n * 3 AS INTEGER) AS col_mediumint,
    CAST(n * 10 AS INTEGER) AS col_int,
    CAST(n AS BIGINT) * 1000003 AS col_bigint,
    CAST(2147483648 + n AS BIGINT) AS col_unsigned_int,
    CAST(n % 1000 + 0.125 AS REAL) AS col_float,
    CAST(n % 100000 + 0.25 AS DOUBLE) AS col_double,
    CAST(ROUND(n % 100000 / 100.0 + 0.1234, 4) AS DECIMAL(18,4)) AS col_decimal,
    CAST(ROUND(n % 100000 / 50.0 + 0.5678, 4) AS DECIMAL(18,4)) AS col_numeric,
    CAST(CONCAT('C', LPAD(CAST(n AS VARCHAR), 9, '0')) AS CHAR(10)) AS col_char,
    CONCAT('v50_', LPAD(CAST(n AS VARCHAR), 5, '0')) AS col_varchar_50,
    CONCAT('v100_', LPAD(CAST(n AS VARCHAR), 5, '0'), '_stable') AS col_varchar_100,
    CONCAT('v255_', LPAD(CAST(n AS VARCHAR), 5, '0'), '_stable_payload') AS col_varchar_255,
    CONCAT('text-', LPAD(CAST(n AS VARCHAR), 5, '0')) AS col_text,
    CONCAT('mediumtext-', LPAD(CAST(n AS VARCHAR), 5, '0'), '-', SUBSTR(REPEAT('x', 32), 1, n % 32)) AS col_mediumtext,
    CAST(TO_HEX(n) AS VARBINARY) AS col_binary,
    CAST(TO_HEX(n * 17) AS VARBINARY) AS col_varbinary,
    CAST(TO_HEX(n * 31) AS VARBINARY) AS col_blob,
    CAST('2026-05-01' AS DATE) + (n % 7) AS col_date,
    TIMESTAMP '2026-05-01 00:00:00' + (n % 10000) * INTERVAL '1' SECOND AS col_datetime,
    TIMESTAMP '2026-05-01 00:00:00' + (n % 10000) * INTERVAL '1' SECOND AS col_timestamp,
    TIME '00:00:00' + (n % 86400) * INTERVAL '1' SECOND AS col_time,
    n % 2 = 0 AS col_boolean,
    CAST(n % 2 AS TINYINT) AS col_tinyint_bool,
    CASE n % 4
        WHEN 0 THEN 'new' WHEN 1 THEN 'processing' WHEN 2 THEN 'done' ELSE 'failed'
    END AS col_enum,
    CASE n % 3
        WHEN 0 THEN 'a,b' WHEN 1 THEN 'b' ELSE 'c'
    END AS col_set,
    JSON_PARSE(CONCAT('{"value":"json_', LPAD(CAST(n AS VARCHAR), 5, '0'), '"}')) AS col_json,
    CONCAT('user_', LPAD(CAST(n AS VARCHAR), 5, '0')) AS user_name,
    CONCAT('user_', LPAD(CAST(n AS VARCHAR), 5, '0'), '@example.com') AS email,
    CONCAT('+861380', LPAD(CAST(n AS VARCHAR), 6, '0')) AS phone,
    CONCAT('No.', CAST(n AS VARCHAR), ' Consilens Road') AS address,
    CASE n % 4
        WHEN 0 THEN 'Shanghai' WHEN 1 THEN 'Beijing' WHEN 2 THEN 'Shenzhen' ELSE 'Hangzhou'
    END AS city,
    'CN' AS country,
    LPAD(CAST(n % 1000000 AS VARCHAR), 6, '0') AS postal_code,
    CAST(ROUND(10 + n % 5000 / 10.0, 4) AS DECIMAL(18,4)) AS amount,
    CAST(ROUND(1000 + n % 8000 / 10.0, 4) AS DECIMAL(18,4)) AS balance,
    CAST(ROUND(5000 + n % 3000 / 10.0, 4) AS DECIMAL(18,4)) AS credit_limit,
    CASE n % 4
        WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' WHEN 2 THEN 'pending' ELSE 'blocked'
    END AS status,
    CASE n % 5
        WHEN 0 THEN 'retail' WHEN 1 THEN 'finance' WHEN 2 THEN 'logistics' WHEN 3 THEN 'manufacturing' ELSE 'public'
    END AS category,
    CAST(n % 5 + 1 AS SMALLINT) AS priority,
    CAST(n % 100 + 0.5 AS DOUBLE) AS score,
    TIMESTAMP '2026-05-01 00:00:00' + (n % 10000) * INTERVAL '1' SECOND AS created_at,
    TIMESTAMP '2026-05-01 00:05:00' + (n % 10000) * INTERVAL '1' SECOND AS updated_at,
    CASE WHEN n % 10 = 0 THEN 1 ELSE 0 END AS deleted,
    DATE '2026-05-01' AS dt
FROM seq;

-- =========================================================
-- validation queries
-- =========================================================

SELECT 'trino.memory.consilens_demo.consilens_performance_demo_table' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       MIN(record_id) AS min_record_id,
       MAX(record_id) AS max_record_id,
       ROUND(SUM(amount), 4) AS amount_sum
FROM memory.consilens_demo.consilens_performance_demo_table;

-- =========================================================
-- cleanup helper tables
-- =========================================================

DROP TABLE IF EXISTS memory.consilens_demo.consilens_seq;
