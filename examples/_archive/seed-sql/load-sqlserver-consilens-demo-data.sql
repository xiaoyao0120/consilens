-- SQL Server seed data for Consilens examples.
-- SQL Server 2019+ compatible
-- Target row count: 10000 rows in each table used by the examples.
--
-- Usage:
--   sqlcmd -S localhost,1433 -U "$SQLSERVER_USER" -P "$SQLSERVER_PASSWORD" \
//     -i examples/seed-sql/load-sqlserver-consilens-demo-data.sql
--
-- Note: SQL Server syntax differences from MySQL/PostgreSQL:
--   - NVARCHAR instead of VARCHAR for Unicode support
--   - DECIMAL instead of NUMERIC
--   - DATETIME/DATETIME2 instead of TIMESTAMP
--   - BIT instead of BOOLEAN
--   - IDENTITY for auto-increment (not used here)
--   - TOP instead of LIMIT
--   - No ENUM/SET type, use VARCHAR with CHECK constraint
--   - JSON stored as NVARCHAR(MAX)
--   - No CONNECT BY, use recursive CTE or numbers table

-- =========================================================
-- Create databases
-- =========================================================

IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'consilens_demo')
    CREATE DATABASE consilens_demo;
GO

IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'mydb')
    CREATE DATABASE mydb;
GO

IF NOT EXISTS (SELECT name FROM sys.databases WHERE name = 'diff_results')
    CREATE DATABASE diff_results;
GO

USE consilens_demo;
GO

-- =========================================================
-- sequence helper tables
-- =========================================================

IF OBJECT_ID('dbo.consilens_digits', 'U') IS NOT NULL
    DROP TABLE dbo.consilens_digits;
GO

CREATE TABLE dbo.consilens_digits (
    d INT NOT NULL PRIMARY KEY
);
GO

INSERT INTO dbo.consilens_digits (d)
VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);
GO

IF OBJECT_ID('dbo.consilens_seq', 'U') IS NOT NULL
    DROP TABLE dbo.consilens_seq;
GO

CREATE TABLE dbo.consilens_seq (
    n INT NOT NULL PRIMARY KEY
);
GO

INSERT INTO dbo.consilens_seq (n)
SELECT
    ones.d
        + tens.d * 10
        + hundreds.d * 100
        + thousands.d * 1000
        + 1 AS n
FROM dbo.consilens_digits ones
CROSS JOIN dbo.consilens_digits tens
CROSS JOIN dbo.consilens_digits hundreds
CROSS JOIN dbo.consilens_digits thousands
WHERE
    ones.d
        + tens.d * 10
        + hundreds.d * 100
        + thousands.d * 1000 < 10000
ORDER BY n;
GO

-- =========================================================
-- consilens_performance_demo_table
-- =========================================================

IF OBJECT_ID('dbo.consilens_performance_demo_table', 'U') IS NOT NULL
    DROP TABLE dbo.consilens_performance_demo_table;
GO

CREATE TABLE dbo.consilens_performance_demo_table (
    record_id NVARCHAR(16) NOT NULL,
    col_tinyint TINYINT,
    col_smallint SMALLINT,
    col_mediumint INT,
    col_int INT,
    col_bigint BIGINT,
    col_unsigned_int BIGINT,
    col_float REAL,
    col_double FLOAT,
    col_decimal DECIMAL(18,4),
    col_numeric DECIMAL(18,4),
    col_char CHAR(10),
    col_varchar_50 NVARCHAR(50),
    col_varchar_100 NVARCHAR(100),
    col_varchar_255 NVARCHAR(255),
    col_text NVARCHAR(MAX),
    col_mediumtext NVARCHAR(MAX),
    col_binary BINARY(8),
    col_varbinary VARBINARY(16),
    col_blob VARBINARY(MAX),
    col_date DATE,
    col_datetime DATETIME2,
    col_timestamp DATETIME2,
    col_time TIME,
    col_boolean BIT,
    col_tinyint_bool TINYINT,
    col_enum NVARCHAR(20),
    col_set NVARCHAR(20),
    col_json NVARCHAR(MAX),
    user_name NVARCHAR(64),
    email NVARCHAR(128),
    phone NVARCHAR(32),
    address NVARCHAR(255),
    city NVARCHAR(64),
    country NVARCHAR(64),
    postal_code NVARCHAR(20),
    amount DECIMAL(18,4),
    balance DECIMAL(18,4),
    credit_limit DECIMAL(18,4),
    status NVARCHAR(20),
    category NVARCHAR(30),
    priority SMALLINT,
    score FLOAT,
    created_at DATETIME2,
    updated_at DATETIME2,
    deleted TINYINT NOT NULL DEFAULT 0,
    dt DATE NOT NULL,
    CONSTRAINT PK_performance_demo PRIMARY KEY (record_id)
);
GO

CREATE INDEX idx_performance_record_id ON dbo.consilens_performance_demo_table(record_id);
CREATE INDEX idx_performance_dt ON dbo.consilens_performance_demo_table(dt);
GO

INSERT INTO dbo.consilens_performance_demo_table (
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
    CONCAT('REC', RIGHT('0000000000' + CAST(n AS VARCHAR(10)), 10)) AS record_id,
    CAST(MOD(n, 100) AS TINYINT) AS col_tinyint,
    CAST(MOD(n, 30000) AS SMALLINT) AS col_smallint,
    n * 3 AS col_mediumint,
    n * 10 AS col_int,
    CAST(n AS BIGINT) * 1000003 AS col_bigint,
    CAST(2147483648 + n AS BIGINT) AS col_unsigned_int,
    CAST(MOD(n, 1000) + 0.125 AS REAL) AS col_float,
    CAST(MOD(n, 100000) + 0.25 AS FLOAT) AS col_double,
    CAST(ROUND(MOD(n, 100000) / 100.0 + 0.1234, 4) AS DECIMAL(18,4)) AS col_decimal,
    CAST(ROUND(MOD(n, 100000) / 50.0 + 0.5678, 4) AS DECIMAL(18,4)) AS col_numeric,
    'C' + RIGHT('000000000' + CAST(n AS VARCHAR(10)), 9) AS col_char,
    CONCAT('v50_', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5)) AS col_varchar_50,
    CONCAT('v100_', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5), '_stable') AS col_varchar_100,
    CONCAT('v255_', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5), '_stable_payload') AS col_varchar_255,
    CONCAT('text-', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5)) AS col_text,
    CONCAT('mediumtext-', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5), '-', REPLICATE('x', MOD(n, 32))) AS col_mediumtext,
    CAST(n AS BINARY(8)) AS col_binary,
    CAST(n * 17 AS VARBINARY(16)) AS col_varbinary,
    CAST(n * 31 AS VARBINARY(MAX)) AS col_blob,
    DATEADD(DAY, MOD(n, 7), CAST('2026-05-01' AS DATE)) AS col_date,
    DATEADD(SECOND, MOD(n, 10000), CAST('2026-05-01 00:00:00' AS DATETIME2)) AS col_datetime,
    DATEADD(SECOND, MOD(n, 10000), CAST('2026-05-01 00:00:00' AS DATETIME2)) AS col_timestamp,
    CONVERT(TIME, DATEADD(SECOND, MOD(n, 86400), CAST('00:00:00' AS DATETIME2))) AS col_time,
    CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END AS col_boolean,
    MOD(n, 2) AS col_tinyint_bool,
    CASE MOD(n, 4)
        WHEN 0 THEN 'new'
        WHEN 1 THEN 'processing'
        WHEN 2 THEN 'done'
        ELSE 'failed'
    END AS col_enum,
    CASE MOD(n, 3)
        WHEN 0 THEN 'a,b'
        WHEN 1 THEN 'b'
        ELSE 'c'
    END AS col_set,
    CONCAT('{"value":"json_', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5), '"}') AS col_json,
    CONCAT('user_', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5)) AS user_name,
    CONCAT('user_', RIGHT('00000' + CAST(n AS VARCHAR(10)), 5), '@example.com') AS email,
    CONCAT('+861380', RIGHT('000000' + CAST(n AS VARCHAR(10)), 6)) AS phone,
    CONCAT('No.', CAST(n AS VARCHAR(10)), ' Consilens Road') AS address,
    CASE MOD(n, 4)
        WHEN 0 THEN 'Shanghai'
        WHEN 1 THEN 'Beijing'
        WHEN 2 THEN 'Shenzhen'
        ELSE 'Hangzhou'
    END AS city,
    'CN' AS country,
    RIGHT('000000' + CAST(MOD(n, 1000000) AS VARCHAR(10)), 6) AS postal_code,
    CAST(ROUND(10 + MOD(n, 5000) / 10.0, 4) AS DECIMAL(18,4)) AS amount,
    CAST(ROUND(1000 + MOD(n, 8000) / 10.0, 4) AS DECIMAL(18,4)) AS balance,
    CAST(ROUND(5000 + MOD(n, 3000) / 10.0, 4) AS DECIMAL(18,4)) AS credit_limit,
    CASE MOD(n, 4)
        WHEN 0 THEN 'active'
        WHEN 1 THEN 'inactive'
        WHEN 2 THEN 'pending'
        ELSE 'blocked'
    END AS status,
    CASE MOD(n, 5)
        WHEN 0 THEN 'retail'
        WHEN 1 THEN 'finance'
        WHEN 2 THEN 'logistics'
        WHEN 3 THEN 'manufacturing'
        ELSE 'public'
    END AS category,
    MOD(n, 5) + 1 AS priority,
    CAST(MOD(n, 100) + 0.5 AS FLOAT) AS score,
    DATEADD(SECOND, MOD(n, 10000), CAST('2026-05-01 00:00:00' AS DATETIME2)) AS created_at,
    DATEADD(SECOND, MOD(n, 10000), CAST('2026-05-01 00:05:00' AS DATETIME2)) AS updated_at,
    CASE WHEN MOD(n, 10) = 0 THEN 1 ELSE 0 END AS deleted,
    CAST('2026-05-01' AS DATE) AS dt
FROM dbo.consilens_seq;
GO

-- =========================================================
-- validation queries
-- =========================================================

SELECT 'sqlserver.consilens_demo.consilens_performance_demo_table' AS check_name,
       COUNT(*) AS actual_rows,
       10000 AS expected_rows,
       MIN(record_id) AS min_record_id,
       MAX(record_id) AS max_record_id,
       ROUND(SUM(amount), 4) AS amount_sum
FROM dbo.consilens_performance_demo_table;
GO

-- =========================================================
-- cleanup helper tables
-- =========================================================

DROP TABLE IF EXISTS dbo.consilens_digits;
DROP TABLE IF EXISTS dbo.consilens_seq;
GO
