-- Oracle seed data for Consilens examples.
-- Oracle 12c+ compatible
-- Target row count: 10000 rows in each table used by the Oracle examples.
--
-- Usage:
--   sqlplus system/oracle@//localhost:1521/ORCL @ examples/seed-sql/load-oracle-consilens-demo-data.sql
--
-- Note: Oracle syntax differences from MySQL/PostgreSQL:
--   - VARCHAR2 instead of VARCHAR
--   - NUMBER instead of DECIMAL/NUMERIC
--   - No LIMIT clause, use ROWNUM or FETCH FIRST
--   - Sequence generation uses CONNECT BY instead of GENERATE_SERIES
--   - DATE type includes time component
--   - Empty string is treated as NULL

ALTER SESSION SET TIME_ZONE = '+08:00';
ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS';
ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS';

-- =========================================================
-- cleanup
-- =========================================================

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE CONSILENS_PERFORMANCE_DEMO_TABLE CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE USERS CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE ORDERS CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE ORDERS_BACKUP CASCADE CONSTRAINTS';
EXCEPTION WHEN OTHERS THEN NULL;
END;
/

-- =========================================================
-- consilens_performance_demo_table
-- =========================================================

CREATE TABLE CONSILENS_PERFORMANCE_DEMO_TABLE (
    RECORD_ID         VARCHAR2(16)   NOT NULL,
    COL_TINYINT       NUMBER(5),
    COL_SMALLINT      NUMBER(5),
    COL_MEDIUMINT     NUMBER(10),
    COL_INT           NUMBER(10),
    COL_BIGINT        NUMBER(19),
    COL_UNSIGNED_INT  NUMBER(19),
    COL_FLOAT         BINARY_FLOAT,
    COL_DOUBLE        BINARY_DOUBLE,
    COL_DECIMAL       NUMBER(18,4),
    COL_NUMERIC       NUMBER(18,4),
    COL_CHAR          CHAR(10),
    COL_VARCHAR_50    VARCHAR2(50),
    COL_VARCHAR_100   VARCHAR2(100),
    COL_VARCHAR_255   VARCHAR2(255),
    COL_TEXT          CLOB,
    COL_MEDIUMTEXT    CLOB,
    COL_BINARY        RAW(8),
    COL_VARBINARY     RAW(16),
    COL_BLOB          BLOB,
    COL_DATE          DATE,
    COL_DATETIME      DATE,
    COL_TIMESTAMP     TIMESTAMP,
    COL_TIME          VARCHAR2(8),
    COL_BOOLEAN       NUMBER(1),
    COL_TINYINT_BOOL  NUMBER(1),
    COL_ENUM          VARCHAR2(20),
    COL_SET           VARCHAR2(20),
    COL_JSON          CLOB,
    USER_NAME         VARCHAR2(64),
    EMAIL             VARCHAR2(128),
    PHONE             VARCHAR2(32),
    ADDRESS           VARCHAR2(255),
    CITY              VARCHAR2(64),
    COUNTRY           VARCHAR2(64),
    POSTAL_CODE       VARCHAR2(20),
    AMOUNT            NUMBER(18,4),
    BALANCE           NUMBER(18,4),
    CREDIT_LIMIT      NUMBER(18,4),
    STATUS            VARCHAR2(20),
    CATEGORY          VARCHAR2(30),
    PRIORITY          NUMBER(5),
    SCORE             BINARY_DOUBLE,
    CREATED_AT        DATE,
    UPDATED_AT        TIMESTAMP,
    DELETED           NUMBER(1)      NOT NULL DEFAULT 0,
    DT                DATE           NOT NULL,
    CONSTRAINT PK_PERFORMANCE_DEMO PRIMARY KEY (RECORD_ID)
);

CREATE INDEX IDX_PERFORMANCE_RECORD_ID ON CONSILENS_PERFORMANCE_DEMO_TABLE(RECORD_ID);
CREATE INDEX IDX_PERFORMANCE_DT ON CONSILENS_PERFORMANCE_DEMO_TABLE(DT);

-- =========================================================
-- sequence helper: generate 1..10000 using CONNECT BY
-- =========================================================

INSERT INTO CONSILENS_PERFORMANCE_DEMO_TABLE (
    RECORD_ID,
    COL_TINYINT,
    COL_SMALLINT,
    COL_MEDIUMINT,
    COL_INT,
    COL_BIGINT,
    COL_UNSIGNED_INT,
    COL_FLOAT,
    COL_DOUBLE,
    COL_DECIMAL,
    COL_NUMERIC,
    COL_CHAR,
    COL_VARCHAR_50,
    COL_VARCHAR_100,
    COL_VARCHAR_255,
    COL_TEXT,
    COL_MEDIUMTEXT,
    COL_BINARY,
    COL_VARBINARY,
    COL_BLOB,
    COL_DATE,
    COL_DATETIME,
    COL_TIMESTAMP,
    COL_TIME,
    COL_BOOLEAN,
    COL_TINYINT_BOOL,
    COL_ENUM,
    COL_SET,
    COL_JSON,
    USER_NAME,
    EMAIL,
    PHONE,
    ADDRESS,
    CITY,
    COUNTRY,
    POSTAL_CODE,
    AMOUNT,
    BALANCE,
    CREDIT_LIMIT,
    STATUS,
    CATEGORY,
    PRIORITY,
    SCORE,
    CREATED_AT,
    UPDATED_AT,
    DELETED,
    DT
)
SELECT
    'REC' || LPAD(n, 10, '0') AS RECORD_ID,
    MOD(n, 100) AS COL_TINYINT,
    MOD(n, 30000) AS COL_SMALLINT,
    n * 3 AS COL_MEDIUMINT,
    n * 10 AS COL_INT,
    n * 1000003 AS COL_BIGINT,
    2147483648 + n AS COL_UNSIGNED_INT,
    CAST(MOD(n, 1000) + 0.125 AS BINARY_FLOAT) AS COL_FLOAT,
    CAST(MOD(n, 100000) + 0.25 AS BINARY_DOUBLE) AS COL_DOUBLE,
    ROUND(MOD(n, 100000) / 100 + 0.1234, 4) AS COL_DECIMAL,
    ROUND(MOD(n, 100000) / 50 + 0.5678, 4) AS COL_NUMERIC,
    'C' || LPAD(n, 9, '0') AS COL_CHAR,
    'v50_' || LPAD(n, 5, '0') AS COL_VARCHAR_50,
    'v100_' || LPAD(n, 5, '0') || '_stable' AS COL_VARCHAR_100,
    'v255_' || LPAD(n, 5, '0') || '_stable_payload' AS COL_VARCHAR_255,
    'text-' || LPAD(n, 5, '0') AS COL_TEXT,
    'mediumtext-' || LPAD(n, 5, '0') || '-' || RPAD('x', MOD(n, 32), 'x') AS COL_MEDIUMTEXT,
    HEXTORAW(LPAD(TO_CHAR(n, 'FMXXXXXXXXXXXXXXXX'), 16, '0')) AS COL_BINARY,
    HEXTORAW(LPAD(TO_CHAR(n * 17, 'FMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX'), 32, '0')) AS COL_VARBINARY,
    HEXTORAW(LPAD(TO_CHAR(n * 31, 'FMXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX'), 32, '0')) AS COL_BLOB,
    TO_DATE('2026-05-01', 'YYYY-MM-DD') + MOD(n, 7) AS COL_DATE,
    TO_DATE('2026-05-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400 AS COL_DATETIME,
    TIMESTAMP '2026-05-01 00:00:00' + NUMTODSINTERVAL(MOD(n, 10000), 'SECOND') AS COL_TIMESTAMP,
    TO_CHAR(MOD(n, 86400) / 86400, 'HH24:MI:SS') AS COL_TIME,
    CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END AS COL_BOOLEAN,
    MOD(n, 2) AS COL_TINYINT_BOOL,
    CASE MOD(n, 4)
        WHEN 0 THEN 'new'
        WHEN 1 THEN 'processing'
        WHEN 2 THEN 'done'
        ELSE 'failed'
    END AS COL_ENUM,
    CASE MOD(n, 3)
        WHEN 0 THEN 'a,b'
        WHEN 1 THEN 'b'
        ELSE 'c'
    END AS COL_SET,
    '{"value":"json_' || LPAD(n, 5, '0') || '"}' AS COL_JSON,
    'user_' || LPAD(n, 5, '0') AS USER_NAME,
    'user_' || LPAD(n, 5, '0') || '@example.com' AS EMAIL,
    '+861380' || LPAD(n, 6, '0') AS PHONE,
    'No.' || n || ' Consilens Road' AS ADDRESS,
    CASE MOD(n, 4)
        WHEN 0 THEN 'Shanghai'
        WHEN 1 THEN 'Beijing'
        WHEN 2 THEN 'Shenzhen'
        ELSE 'Hangzhou'
    END AS CITY,
    'CN' AS COUNTRY,
    LPAD(MOD(n, 1000000), 6, '0') AS POSTAL_CODE,
    ROUND(10 + MOD(n, 5000) / 10, 4) AS AMOUNT,
    ROUND(1000 + MOD(n, 8000) / 10, 4) AS BALANCE,
    ROUND(5000 + MOD(n, 3000) / 10, 4) AS CREDIT_LIMIT,
    CASE MOD(n, 4)
        WHEN 0 THEN 'active'
        WHEN 1 THEN 'inactive'
        WHEN 2 THEN 'pending'
        ELSE 'blocked'
    END AS STATUS,
    CASE MOD(n, 5)
        WHEN 0 THEN 'retail'
        WHEN 1 THEN 'finance'
        WHEN 2 THEN 'logistics'
        WHEN 3 THEN 'manufacturing'
        ELSE 'public'
    END AS CATEGORY,
    MOD(n, 5) + 1 AS PRIORITY,
    CAST(MOD(n, 100) + 0.5 AS BINARY_DOUBLE) AS SCORE,
    TO_DATE('2026-05-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400 AS CREATED_AT,
    TIMESTAMP '2026-05-01 00:05:00' + NUMTODSINTERVAL(MOD(n, 10000), 'SECOND') AS UPDATED_AT,
    CASE WHEN MOD(n, 10) = 0 THEN 1 ELSE 0 END AS DELETED,
    TO_DATE('2026-05-01', 'YYYY-MM-DD') AS DT
FROM (
    SELECT LEVEL AS n
    FROM DUAL
    CONNECT BY LEVEL <= 10000
);

-- =========================================================
-- users
-- =========================================================

CREATE TABLE USERS (
    ID          NUMBER(10)      NOT NULL PRIMARY KEY,
    NAME        VARCHAR2(100),
    EMAIL       VARCHAR2(128)   NOT NULL,
    PHONE       VARCHAR2(32),
    STATUS      VARCHAR2(20),
    CREATED_AT  DATE
);

CREATE INDEX IDX_USERS_EMAIL ON USERS(EMAIL);
CREATE INDEX IDX_USERS_CREATED_AT ON USERS(CREATED_AT);

INSERT INTO USERS (ID, NAME, EMAIL, PHONE, STATUS, CREATED_AT)
SELECT
    n AS ID,
    'user_' || LPAD(n, 5, '0') AS NAME,
    'user_' || LPAD(n, 5, '0') || '@example.com' AS EMAIL,
    '+861380' || LPAD(n, 6, '0') AS PHONE,
    CASE MOD(n, 3)
        WHEN 0 THEN 'active'
        WHEN 1 THEN 'inactive'
        ELSE 'pending'
    END AS STATUS,
    TO_DATE('2026-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 10000) / 86400 AS CREATED_AT
FROM (
    SELECT LEVEL AS n
    FROM DUAL
    CONNECT BY LEVEL <= 10000
);

-- =========================================================
-- orders
-- =========================================================

CREATE TABLE ORDERS (
    ORDER_ID     NUMBER(19)     NOT NULL PRIMARY KEY,
    CUSTOMER_ID  NUMBER(10)     NOT NULL,
    AMOUNT       NUMBER(18,4)   NOT NULL,
    STATUS       VARCHAR2(20)   NOT NULL,
    CREATED_AT   DATE           NOT NULL
);

CREATE INDEX IDX_ORDERS_CREATED_AT ON ORDERS(CREATED_AT);

INSERT INTO ORDERS (ORDER_ID, CUSTOMER_ID, AMOUNT, STATUS, CREATED_AT)
SELECT
    n AS ORDER_ID,
    100000 + MOD(n, 500) AS CUSTOMER_ID,
    ROUND(20 + MOD(n, 10000) / 20, 4) AS AMOUNT,
    CASE MOD(n, 4)
        WHEN 0 THEN 'paid'
        WHEN 1 THEN 'created'
        WHEN 2 THEN 'shipped'
        ELSE 'closed'
    END AS STATUS,
    TO_DATE('2025-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS') + MOD(n, 365) AS CREATED_AT
FROM (
    SELECT LEVEL AS n
    FROM DUAL
    CONNECT BY LEVEL <= 10000
);

-- =========================================================
-- orders_backup (copy from orders for same-db comparison)
-- =========================================================

CREATE TABLE ORDERS_BACKUP AS SELECT * FROM ORDERS WHERE 1=0;

INSERT INTO ORDERS_BACKUP
SELECT * FROM ORDERS;

-- =========================================================
-- validation queries
-- =========================================================

SELECT 'oracle.CONSILENS_PERFORMANCE_DEMO_TABLE' AS CHECK_NAME,
       COUNT(*) AS ACTUAL_ROWS,
       10000 AS EXPECTED_ROWS,
       MIN(RECORD_ID) AS MIN_RECORD_ID,
       MAX(RECORD_ID) AS MAX_RECORD_ID,
       ROUND(SUM(AMOUNT), 4) AS AMOUNT_SUM
FROM CONSILENS_PERFORMANCE_DEMO_TABLE;

SELECT 'oracle.USERS' AS CHECK_NAME,
       COUNT(*) AS ACTUAL_ROWS,
       10000 AS EXPECTED_ROWS,
       MIN(ID) AS MIN_ID,
       MAX(ID) AS MAX_ID
FROM USERS;

SELECT 'oracle.ORDERS' AS CHECK_NAME,
       COUNT(*) AS ACTUAL_ROWS,
       10000 AS EXPECTED_ROWS,
       ROUND(SUM(AMOUNT), 4) AS AMOUNT_SUM
FROM ORDERS;

SELECT 'oracle.ORDERS_BACKUP' AS CHECK_NAME,
       COUNT(*) AS ACTUAL_ROWS,
       10000 AS EXPECTED_ROWS,
       ROUND(SUM(AMOUNT), 4) AS AMOUNT_SUM
FROM ORDERS_BACKUP;

COMMIT;
