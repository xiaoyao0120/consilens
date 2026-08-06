package com.consilens.connector.trino;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.DataTypeHandler;
import com.consilens.conncetor.base.BaseSqlQueryGenerator;

import com.consilens.connector.api.model.DataType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Trino SQL query generator.
 * Uses OFFSET...LIMIT for pagination (ANSI SQL standard).
 */
public class TrinoSqlQueryGenerator extends BaseSqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;
    private final DataTypeHandler dataTypeHandler;

    public TrinoSqlQueryGenerator(CapabilityProvider capabilityProvider,
            DataTypeHandler dataTypeHandler) {
        super(capabilityProvider);
        this.capabilityProvider = capabilityProvider;
        this.dataTypeHandler = dataTypeHandler;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            return "LIMIT " + limit;
        }
        return "OFFSET " + offset + " LIMIT " + limit;
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
            sql.append("COALESCE(lower(to_hex(md5(to_utf8(array_join(array_agg(row_checksum ORDER BY pk_key), '|'))))), '') as checksum ");
            sql.append("FROM (SELECT ");

            // Build primary key for stable ordering
            // Trino CONCAT requires at least 2 arguments, so handle single key specially
            if (keyColumns.size() == 1) {
                String col = keyColumns.get(0);
                DataType dataType = columnDataTypes.get(col);
                sql.append(formatForChecksum(col, dataType)).append(" as pk_key, ");
            } else {
                sql.append("CONCAT(");
                for (int i = 0; i < keyColumns.size(); i++) {
                    if (i > 0)
                        sql.append(", '|', ");
                    String col = keyColumns.get(i);
                    DataType dataType = columnDataTypes.get(col);
                    sql.append(formatForChecksum(col, dataType));
                }
                sql.append(") as pk_key, ");
            }

            // Build per-row checksum using MD5
            sql.append("lower(to_hex(md5(to_utf8(CONCAT(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0)
                    sql.append(", '|', ");
                String col = columns.get(i);
                DataType dataType = columnDataTypes.get(col);
                sql.append(formatForChecksum(col, dataType));
            }
            sql.append("))))) as row_checksum");
        }

        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }
        
        if (!columns.isEmpty()) {
            sql.append(") t");
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

        sql.append("SELECT ");
        for (int i = 0; i < primaryKeys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(capabilityProvider.quote(primaryKeys.get(i)));
        }

        sql.append(", lower(to_hex(md5(to_utf8(CONCAT(");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", '|', ");
            }
            String col = columns.get(i);
            DataType dataType = columnDataTypes.get(col);
            sql.append(formatForChecksum(col, dataType));
        }

        sql.append("))))) AS row_hash");

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

    /**
     * Format a column for checksum calculation, applying type-specific formatting
     * for temporal types (Date, DateTime, Timestamp).
     *
     * <p>normalizeDate/normalizeDateTime etc. now return the raw column to avoid
     * type mismatches in WHERE clauses. This method re-applies the proper formatting
     * when the column is used inside the checksum concat().
     */
    private String formatForChecksum(String quotedCol, DataType dataType) {
        if (dataType == null) {
            return dataTypeHandler.normalizeColumn(quotedCol, null);
        }
        String typeName = dataType.name().toLowerCase();
        if (dataTypeHandler instanceof TrinoDataTypeHandler) {
            TrinoDataTypeHandler trinoHandler = (TrinoDataTypeHandler) dataTypeHandler;
            if (typeName.contains("date") && !typeName.contains("time")) {
                return trinoHandler.formatDateForChecksum(quotedCol);
            }
            if (typeName.contains("time") || typeName.contains("datetime") || typeName.contains("timestamp")) {
                return trinoHandler.formatDateTimeForChecksum(quotedCol, typeName);
            }
        }
        return dataTypeHandler.normalizeColumn(quotedCol, dataType);
    }

    /**
     * Trino does not have CONCAT_WS. Use ARRAY_JOIN(ARRAY[...], sep) instead.
     */
    @Override
    protected String stringJoin(String separator, List<String> args) {
        return "ARRAY_JOIN(ARRAY[" + String.join(", ", args) + "], " + separator + ")";
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
