package com.consilens.connector.sqlserver;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SQL Server SQL query generator.
 * Uses TOP for limiting results and OFFSET...FETCH for pagination.
 */
public class SQLServerSqlQueryGenerator extends BaseSqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public SQLServerSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }


    /** join 计划比较列的类型归一化处理器（与 checksum 路径同口径）。 */
    @Override
    protected com.consilens.connector.api.DataTypeHandler dataTypeHandler() {
        return this.dataTypeHandler;
    }

    /** 归一化结果为字符串时，须做字节级比较以规避库内排序规则（如 utf8mb4_0900_ai_ci）。 */
    @Override
    protected String binarySafeExpression(String expression) {
        return "CONVERT(VARBINARY(MAX), " + utf8Text(expression) + ")";
    }

    /**
     * UTF-8 文本化：SQL Server 的 {@code CONVERT(VARBINARY, nvarchar)} 取的是 UTF-16 字节，
     * {@code HASHBYTES} 对 nvarchar 也按 UTF-16 摘要，与其它方言（MD5 over UTF-8）不一致——
     * 跨源比对时同一行的摘要必然不同（实测 mysql↔sqlserver 全表 9989 行误报）。
     * 先按 UTF-8 排序规则转成 varchar 再取字节/摘要，字节序即与 MySQL 的 utf8mb4 一致。
     */
    private String utf8Text(String expression) {
        return "CONVERT(VARCHAR(MAX), " + expression + " COLLATE Latin1_General_100_CI_AS_SC_UTF8)";
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            return ""; // Use TOP in SELECT clause
        }
        // SQL Server 2012+ OFFSET...FETCH syntax
        return "OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * Generate SELECT with TOP for SQL Server.
     * Note: This is used when offset is 0.
     */
    public String getTopClause(long limit) {
        return "TOP " + limit;
    }

    @Override
    public String getChecksumSQL(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) as row_count, ");

        if (columns.isEmpty()) {
            sql.append("'' as checksum ");
        } else {
            // Two-step approach matching MySQL: per-row MD5 + aggregate MD5
            // Note: STRING_AGG returns VARCHAR(8000) by default which overflows for large tables.
            // Cast row_checksum to VARCHAR(MAX) so the aggregate result is also VARCHAR(MAX).
            sql.append("COALESCE(CONVERT(VARCHAR(MAX), HASHBYTES('MD5', ");
            // 聚合串只含 32 位 hex 与 '|'（纯 ASCII），UTF-8 与 ASCII 同字节，无需转换
            sql.append("(SELECT STRING_AGG(CAST(row_checksum AS VARCHAR(MAX)), '|') WITHIN GROUP (ORDER BY pk_key, row_checksum) FROM (SELECT ");

            // Build primary key for stable ordering
            // SQL Server CONCAT_WS requires 3+ args (sep + 2+ exprs), so handle single key specially
            if (keyColumns.size() == 1) {
                sql.append(dataTypeHandler.normalizeColumn(keyColumns.get(0), columnDataTypes.get(keyColumns.get(0))));
                sql.append(" as pk_key, ");
            } else {
                sql.append("CONCAT_WS('|', ");
                for (int i = 0; i < keyColumns.size(); i++) {
                    if (i > 0)
                        sql.append(", ");
                    String col = keyColumns.get(i);
                    DataType dataType = columnDataTypes.get(col);
                    sql.append(dataTypeHandler.normalizeColumn(col, dataType));
                }
                sql.append(") as pk_key, ");
            }

            // Build per-row checksum using HASHBYTES MD5
            // SQL Server CONCAT_WS requires 3+ args, so use CONCAT for single column
            // 行摘要必须按 UTF-8 字节：否则含非 ASCII 的行摘要与 MySQL 侧不同（全表误报）
            String concatExpr;
            if (columns.size() == 1) {
                String col = columns.get(0);
                concatExpr = "CONCAT('|', "
                        + dataTypeHandler.normalizeColumn(col, columnDataTypes.get(col)) + ")";
            } else {
                StringBuilder concat = new StringBuilder("CONCAT_WS('|'");
                for (String col : columns) {
                    concat.append(", ").append(dataTypeHandler.normalizeColumn(col, columnDataTypes.get(col)));
                }
                concat.append(")");
                concatExpr = concat.toString();
            }
            sql.append("CONVERT(VARCHAR(32), HASHBYTES('MD5', ")
                    .append(utf8Text(concatExpr))
                    .append("), 2) as row_checksum");

            sql.append(" FROM ");
            sql.append(buildRelationRef(schemaName, tableName));
            if (whereClause != null && !whereClause.trim().isEmpty()) {
                sql.append(" WHERE ").append(whereClause);
            }

            sql.append(") t)), 2), '') as checksum ");
        }

        sql.append("FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }
    @Override
    public String getRowHashSQL(String schemaName, String tableName,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        // SELECT primary key columns and row_hash
        sql.append("SELECT ");

        // Add primary key columns
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        StringBuilder rowHashConcat = new StringBuilder("CONCAT(");

        // Build canonical expression using CHAR(31) as separator
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                rowHashConcat.append(", CHAR(31), ");
            }
            String col = columns.get(i);
            DataType dataType = columnDataTypes.get(col);
            String normalizedCol = dataTypeHandler.normalizeColumn(col, dataType);

            // Use ISNULL to convert NULL to CHAR(1) (unified across all databases)
            rowHashConcat.append("ISNULL(").append(normalizedCol).append(", CHAR(1))");
        }
        rowHashConcat.append(")");

        // 同 getChecksumSQL：按 UTF-8 字节摘要，保证跨源行哈希可比
        sql.append(", CONVERT(VARCHAR(32), HASHBYTES('MD5', ")
                .append(utf8Text(rowHashConcat.toString()))
                .append("), 2) as row_hash");

        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getFullOuterJoinSQL(String table1, String table2,
            List<String> joinColumns,
            String whereClause) {
        StringBuilder sql = new StringBuilder();

        sql.append("SELECT * FROM ").append(table1)
                .append(" FULL OUTER JOIN ").append(table2)
                .append(" ON ").append(buildJoinCondition(table1, table2, joinColumns));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getBatchInsertSQL(String tableName, List<String> columns, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least 1");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(capabilityProvider.quote(tableName)).append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(capabilityProvider.quote(columns.get(i)));
        }
        sql.append(") VALUES ");

        String valuePlaceholder = "(" +
                String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";

        for (int i = 0; i < batchSize; i++) {
            if (i > 0)
                sql.append(", ");
            sql.append(valuePlaceholder);
        }

        return sql.toString();
    }

    private String buildJoinCondition(String table1, String table2, List<String> joinColumns) {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < joinColumns.size(); i++) {
            if (i > 0)
                condition.append(" AND ");
            condition.append(capabilityProvider.quote(table1)).append(".")
                    .append(capabilityProvider.quote(joinColumns.get(i)))
                    .append(" = ")
                    .append(capabilityProvider.quote(table2)).append(".")
                    .append(capabilityProvider.quote(joinColumns.get(i)));
        }
        return condition.toString();
    }
}
