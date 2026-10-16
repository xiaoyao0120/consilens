package com.consilens.conncetor.base;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.enums.DatabaseFeature;
import com.consilens.connector.api.model.DataType;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public abstract class BaseSqlQueryGenerator implements SqlQueryGenerator {

    private final CapabilityProvider capabilityProvider;

    public BaseSqlQueryGenerator(CapabilityProvider capabilityProvider) {
        this.capabilityProvider = capabilityProvider;
    }

    @Override
    public String getLimitClause(long limit) {
        return "LIMIT " + limit;
    }

    @Override
    public String getLimitClause(long offset, long limit) {
        if (offset <= 0) {
            return getLimitClause(limit);
        }
        // Default implementation for databases that support LIMIT with OFFSET
        return "LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public String getCountSQL(String schemaName, String tableName, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getCountSQLFromSql(String fromSql, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ");
        sql.append(buildSqlRelationRef(fromSql));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getSelectSQL(String schemaName, String tableName,
            List<String> selectExpressions,
            String whereClause,
            List<String> orderByColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(String.join(", ", selectExpressions));
        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (orderByColumns != null && !orderByColumns.isEmpty()) {
            sql.append(" ORDER BY ");
            sql.append(String.join(", ",
                    orderByColumns.stream().map(this::orderByExpression).toArray(String[]::new)));
        }

        return sql.toString();
    }

    /**
     * join 查询尾部的方言设置子句（默认空）。ClickHouse 覆盖为
     * {@code " SETTINGS join_use_nulls = 1"}——它默认把外连接未匹配侧填成列默认值（0/''）而非 NULL，
     * presence 归因（t1.k IS NULL）会全部失效（实测 join 把 5 TM + 6 SM 全报成 69 MM）。
     */
    protected String joinSettingsClause() {
        return "";
    }

    /**
     * ORDER BY 表达式：本地比对（streaming-merge）要求两侧用**同一套排序语义**做归并，
     * 否则一侧按数字序、另一侧按字典序时归并会错位（实测 Oracle typediff：A 侧 id 是
     * NUMBER、B 侧是 VARCHAR2，同键行被判成互不匹配的 SM/TM）。统一按字符串形态排序。
     *
     * <p>注意空值位置：Java 侧归并把归一化后的 NULL 当空串（排在最前），因此空值必须
     * **在最前**才能对齐。MySQL/Doris/StarRocks 升序默认 NULLS FIRST；Oracle 显式
     * {@code NULLS FIRST}；PostgreSQL/Presto/Trino 升序默认 NULLS LAST，若将来有方言
     * 走上 streaming-merge 且默认 NULLS LAST，必须覆盖本方法补 {@code NULLS FIRST}。
     */
    protected String orderByExpression(String column) {
        return "CAST(" + capabilityProvider.quote(column) + " AS VARCHAR)";
    }

    @Override
    public String getDuplicateKeyProbeSQL(String schemaName, String tableName, List<String> keyColumns,
            Map<String, DataType> keyTypes, String whereClause) {
        // 分组用二进制安全表达式（跨方言判等一致），展示用归一化文本表达式——
        // 后者经 MIN() 聚合以兼容要求 SELECT 列必须出现在 GROUP BY 的方言（Oracle 等）。
        // 直接 SELECT 二进制安全表达式会让证据不可读（MySQL HEX → '3230'、Oracle RAW → 十六进制）。
        List<String> normalizedExprs = normalizedKeyExpressions(keyColumns, keyTypes, null);
        List<String> groupExprs = new java.util.ArrayList<>();
        List<String> displayExprs = new java.util.ArrayList<>();
        for (String normalized : normalizedExprs) {
            groupExprs.add(binarySafeExpression(normalized));
            displayExprs.add("MIN(" + normalized + ")");
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(String.join(", ", displayExprs)).append(", COUNT(*) AS nl_dup_count FROM ");
        sql.append(buildRelationRef(schemaName, tableName));
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }
        sql.append(" GROUP BY ").append(String.join(", ", groupExprs)).append(" HAVING COUNT(*) > 1");
        return sql.toString();
    }

    @Override
    public String getKeyMultiplicityFilterSQL(String schemaName, String tableName,
            List<String> keyColumns, Map<String, DataType> keyTypes, String whereClause, boolean duplicates,
            String outerQualifier) {
        // 外层（本行）键表达式：与 join 条件同口径（归一化 + 二进制安全）。
        // 必须限定外层表名——非限定列在子查询里会绑定到子查询自己的别名（自比较退化）。
        List<String> outerExprs = normalizedKeyExpressions(keyColumns, keyTypes,
                outerQualifier != null ? capabilityProvider.quote(outerQualifier) : null);
        for (int i = 0; i < outerExprs.size(); i++) {
            outerExprs.set(i, binarySafeExpression(outerExprs.get(i)));
        }
        // 内层（同一张表的独立扫描）：带别名，避免与外层同名解析冲突。
        String innerAlias = "nl_dup_k";
        List<String> innerNorm = normalizedKeyExpressions(keyColumns, keyTypes, innerAlias);
        List<String> innerGroup = new java.util.ArrayList<>();
        for (String normalized : innerNorm) {
            innerGroup.add(binarySafeExpression(normalized));
        }
        String having = duplicates ? " > 1" : " = 1";

        StringBuilder inner = new StringBuilder("SELECT 1 FROM ");
        inner.append(buildRelationRef(schemaName, tableName)).append(" ").append(innerAlias);
        if (whereClause != null && !whereClause.trim().isEmpty()) {
            inner.append(" WHERE ").append(whereClause).append(" AND ");
        } else {
            inner.append(" WHERE ");
        }
        for (int i = 0; i < outerExprs.size(); i++) {
            if (i > 0) {
                inner.append(" AND ");
            }
            inner.append(innerGroup.get(i)).append(" = ").append(outerExprs.get(i));
        }
        inner.append(" GROUP BY ").append(String.join(", ", innerGroup))
                .append(" HAVING COUNT(*)").append(having);
        return "EXISTS (" + inner + ")";
    }

    /**
     * 键列的归一化表达式（可选别名前缀）。探针与多重性过滤器共用，
     * 保证与 join 条件/checksum 同一比对口径（NULL→''、TRIM、类型形态）。
     */
    private List<String> normalizedKeyExpressions(List<String> keyColumns, Map<String, DataType> keyTypes,
            String alias) {
        com.consilens.connector.api.DataTypeHandler handler = dataTypeHandler();
        List<String> exprs = new java.util.ArrayList<>(keyColumns.size());
        for (String column : keyColumns) {
            String quoted = capabilityProvider.quote(column);
            if (alias != null) {
                quoted = alias + "." + quoted;
            }
            DataType type = keyTypes == null ? null : keyTypes.get(column);
            exprs.add(handler != null ? handler.normalizeExpression(quoted, type) : quoted);
        }
        return exprs;
    }

    @Override
    public String getSelectSQLFromSql(String fromSql,
            List<String> selectExpressions,
            String whereClause,
            List<String> orderByColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(String.join(", ", selectExpressions));
        sql.append(" FROM ");
        sql.append(buildSqlRelationRef(fromSql));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (orderByColumns != null && !orderByColumns.isEmpty()) {
            sql.append(" ORDER BY ");
            sql.append(String.join(", ",
                    orderByColumns.stream().map(this::orderByExpression).toArray(String[]::new)));
        }

        return sql.toString();
    }

    @Override
    public String getSelectByKeysSQL(String schemaName, String tableName,
            List<String> selectExpressions,
            List<String> keyColumns,
            List<List<Object>> primaryKeys,
            String whereClause,
            List<String> orderByColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(String.join(", ", selectExpressions));
        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        String keyPredicate = buildKeyPredicate(keyColumns, primaryKeys);
        boolean hasKeyPredicate = keyPredicate != null && !keyPredicate.isEmpty();
        boolean hasWhereClause = whereClause != null && !whereClause.trim().isEmpty();

        if (hasKeyPredicate) {
            sql.append(" WHERE ").append(keyPredicate);
            if (hasWhereClause) {
                sql.append(" AND (").append(whereClause).append(")");
            }
        } else if (hasWhereClause) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (orderByColumns != null && !orderByColumns.isEmpty()) {
            sql.append(" ORDER BY ");
            sql.append(String.join(", ",
                    orderByColumns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        }

        return sql.toString();
    }

    @Override
    public String getSelectByKeysSQLFromSql(String fromSql,
            List<String> selectExpressions,
            List<String> keyColumns,
            List<List<Object>> primaryKeys,
            String whereClause,
            List<String> orderByColumns) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(String.join(", ", selectExpressions));
        sql.append(" FROM ");
        sql.append(buildSqlRelationRef(fromSql));

        String keyPredicate = buildKeyPredicate(keyColumns, primaryKeys);
        boolean hasKeyPredicate = keyPredicate != null && !keyPredicate.isEmpty();
        boolean hasWhereClause = whereClause != null && !whereClause.trim().isEmpty();

        if (hasKeyPredicate) {
            sql.append(" WHERE ").append(keyPredicate);
            if (hasWhereClause) {
                sql.append(" AND (").append(whereClause).append(")");
            }
        } else if (hasWhereClause) {
            sql.append(" WHERE ").append(whereClause);
        }

        if (orderByColumns != null && !orderByColumns.isEmpty()) {
            sql.append(" ORDER BY ");
            sql.append(String.join(", ",
                    orderByColumns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        }

        return sql.toString();
    }

    @Override
    public String getMinMaxKeySQL(String schemaName, String tableName,
            List<String> keyColumns,
            boolean getMin,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String column = capabilityProvider.quote(keyColumns.get(i));
            sql.append(getMin ? "MIN(" : "MAX(").append(column).append(")");
        }
        sql.append(" FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getMinMaxKeySQLFromSql(String fromSql,
            List<String> keyColumns,
            boolean getMin,
            String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String column = capabilityProvider.quote(keyColumns.get(i));
            sql.append(getMin ? "MIN(" : "MAX(").append(column).append(")");
        }
        sql.append(" FROM ");
        sql.append(buildSqlRelationRef(fromSql));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getDistinctCountSQL(String schemaName, String tableName, List<String> columns, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT ");
        sql.append(String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        sql.append(") FROM ");
        sql.append(buildRelationRef(schemaName, tableName));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getDistinctCountSQLFromSql(String fromSql, List<String> columns, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT ");
        sql.append(String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        sql.append(") FROM ");
        sql.append(buildSqlRelationRef(fromSql));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getFullOuterJoinSQL(String table1, String table2, List<String> joinColumns, String whereClause) {
        if (!capabilityProvider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)) {
            // Fallback to left and right outer joins unioned
            String leftJoin = getLeftOuterJoinSQL(table1, table2, joinColumns, whereClause);
            String rightJoin = getRightOuterJoinSQL(table1, table2, joinColumns, whereClause);
            return "(" + leftJoin + ") UNION (" + rightJoin + ")";
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(table1);
        sql.append(" FULL OUTER JOIN ").append(table2);
        sql.append(" ON ").append(buildJoinCondition(joinColumns));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getLeftOuterJoinSQL(String table1, String table2, List<String> joinColumns, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(table1);
        sql.append(" LEFT OUTER JOIN ").append(table2);
        sql.append(" ON ").append(buildJoinCondition(joinColumns));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getRightOuterJoinSQL(String table1, String table2, List<String> joinColumns, String whereClause) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(table1);
        sql.append(" RIGHT OUTER JOIN ").append(table2);
        sql.append(" ON ").append(buildJoinCondition(joinColumns));

        if (whereClause != null && !whereClause.trim().isEmpty()) {
            sql.append(" WHERE ").append(whereClause);
        }

        return sql.toString();
    }

    @Override
    public String getCreateTempTableSQL(String tempTableName, String selectSQL) {
        return "CREATE TEMPORARY TABLE " + capabilityProvider.quote(tempTableName) + " AS " + selectSQL;
    }

    @Override
    public String getDropTableSQL(String tableName, boolean ifExists) {
        return "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + capabilityProvider.quote(tableName);
    }

    @Override
    public String getInsertSQL(String tableName, List<String> columns) {
        return "INSERT INTO " + capabilityProvider.quote(tableName) +
                " (" + String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)) + ")";
    }

    @Override
    public String getBatchInsertSQL(String tableName, List<String> columns, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive, got: " + batchSize);
        }

        if (batchSize == 1) {
            // Single row: INSERT INTO table (col1, col2) VALUES (?, ?)
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ").append(capabilityProvider.quote(tableName)).append(" (");
            sql.append(String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
            sql.append(") VALUES (");
            sql.append(String.join(", ", IntStream.range(0, columns.size())
                    .mapToObj(i -> "?").toArray(String[]::new)));
            sql.append(")");
            return sql.toString();
        }

        // Multiple rows: INSERT INTO table (col1, col2) VALUES (?, ?), (?, ?), ...
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(capabilityProvider.quote(tableName)).append(" (");
        sql.append(String.join(", ", columns.stream().map(capabilityProvider::quote).toArray(String[]::new)));
        sql.append(") VALUES ");

        // Build (?, ?, ...) placeholder
        String valuePlaceholder = "(" +
                String.join(", ", IntStream.range(0, columns.size())
                        .mapToObj(i -> "?").toArray(String[]::new))
                + ")";

        // Repeat placeholder batchSize times, separated by commas
        sql.append(String.join(", ", IntStream.range(0, batchSize)
                .mapToObj(i -> valuePlaceholder).toArray(String[]::new)));

        return sql.toString();
    }

    @Override
    public String formatValue(Object value) {
        return SqlQueryGenerator.super.formatValue(value);
    }


    @Override
    public String getJoinDiffStatsSQL(String schema1, String table1, String alias1,
            List<String> keyColumns1, List<String> compareColumns1, String where1,
            Map<String, DataType> columnTypes1,
            String schema2, String table2, String alias2,
            List<String> keyColumns2, List<String> compareColumns2, String where2,
            Map<String, DataType> columnTypes2) {
        String t1 = buildTableSource(schema1, table1, alias1, where1);
        String t2 = buildTableSource(schema2, table2, alias2, where2);
        String joinCondition = buildJoinCondition(alias1, keyColumns1, alias2, keyColumns2,
                columnTypes1, columnTypes2);
        String diffPredicate = buildDiffPredicate(alias1, alias2, compareColumns1, compareColumns2,
                columnTypes1, columnTypes2);
        String t1KeysNull = buildKeysNullExpression(alias1, keyColumns1);
        String t2KeysNull = buildKeysNullExpression(alias2, keyColumns2);
        // NULL 键语义：两侧键都为 NULL 的"匹配对"归一化后相等，应按普通行比较，
        // 而不是被 IS NULL 归因成同时 SM+TM（旧写法对 NULL 键行恒误报）。
        String rowMatched = "((" + t1KeysNull + " AND " + t2KeysNull + ")"
                + " OR (NOT (" + t1KeysNull + ") AND NOT (" + t2KeysNull + ")))";
        String sourceMissing = "(" + t1KeysNull + " AND NOT (" + t2KeysNull + "))";
        String targetMissing = "(" + t2KeysNull + " AND NOT (" + t1KeysNull + "))";

        // 列形状契约：调用方按 (source_count, target_count, source_missing, target_missing,
        // mismatch) 五列读取（JoinDiffer.collectStatistics / JoinPerformanceMonitor
        // .recordStatistics 少一列即整行丢弃），两条分支必须给出相同列数与顺序。
        if (capabilityProvider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)) {
            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ");
            sql.append("SUM(CASE WHEN ").append(sourceMissing).append(" THEN 0 ELSE 1 END) AS source_count, ");
            sql.append("SUM(CASE WHEN ").append(targetMissing).append(" THEN 0 ELSE 1 END) AS target_count, ");
            sql.append("SUM(CASE WHEN ").append(sourceMissing).append(" THEN 1 ELSE 0 END) AS source_missing, ");
            sql.append("SUM(CASE WHEN ").append(targetMissing).append(" THEN 1 ELSE 0 END) AS target_missing, ");
            sql.append("SUM(CASE WHEN ").append(rowMatched).append(" AND ").append(diffPredicate)
                    .append(" THEN 1 ELSE 0 END) AS mismatch ");
            sql.append("FROM ").append(t1).append(" FULL OUTER JOIN ").append(t2);
            sql.append(" ON ").append(joinCondition);
            sql.append(joinSettingsClause());
            return sql.toString();
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT SUM(source_count) AS source_count, ");
        sql.append("SUM(target_count) AS target_count, ");
        sql.append("SUM(source_missing) AS source_missing, ");
        sql.append("SUM(target_missing) AS target_missing, ");
        sql.append("SUM(mismatch) AS mismatch ");
        sql.append("FROM (");

        sql.append("SELECT ");
        sql.append("SUM(CASE WHEN ").append(sourceMissing).append(" THEN 0 ELSE 1 END) AS source_count, ");
        sql.append("SUM(CASE WHEN ").append(targetMissing).append(" THEN 0 ELSE 1 END) AS target_count, ");
        sql.append("0 AS source_missing, ");
        sql.append("SUM(CASE WHEN ").append(targetMissing).append(" THEN 1 ELSE 0 END) AS target_missing, ");
        sql.append("SUM(CASE WHEN ").append(rowMatched).append(" AND ").append(diffPredicate)
                .append(" THEN 1 ELSE 0 END) AS mismatch ");
        sql.append("FROM ").append(t1).append(" LEFT OUTER JOIN ").append(t2);
        sql.append(" ON ").append(joinCondition);

        sql.append(" UNION ALL ");

        sql.append("SELECT ");
        sql.append("0 AS source_count, ");
        sql.append("SUM(CASE WHEN ").append(targetMissing).append(" THEN 0 ELSE 1 END) AS target_count, ");
        sql.append("SUM(CASE WHEN ").append(sourceMissing).append(" THEN 1 ELSE 0 END) AS source_missing, ");
        sql.append("0 AS target_missing, ");
        sql.append("0 AS mismatch ");
        sql.append("FROM ").append(t2).append(" LEFT OUTER JOIN ").append(t1);
        sql.append(" ON ").append(joinCondition);
        sql.append(" WHERE ").append(sourceMissing);

        sql.append(") s");
        return sql.toString();
    }

    /**
     * 不带列类型的旧接口：语义与带类型版本完全一致，仅比较表达式退化为原始列引用。
     * 保留为委托，避免两份实现各自演化。
     */
    @Override
    public String getJoinDiffStatsSQL(String schema1, String table1, String alias1,
            List<String> keyColumns1, List<String> compareColumns1, String where1,
            String schema2, String table2, String alias2,
            List<String> keyColumns2, List<String> compareColumns2, String where2) {
        return getJoinDiffStatsSQL(schema1, table1, alias1, keyColumns1, compareColumns1, where1, null,
                schema2, table2, alias2, keyColumns2, compareColumns2, where2, null);
    }

    @Override
    public String getJoinDiffDetailSQL(String schema1, String table1, String alias1,
            List<String> keyColumns1, List<String> compareColumns1, List<String> outputColumns1, String where1,
            Map<String, DataType> columnTypes1,
            String schema2, String table2, String alias2,
            List<String> keyColumns2, List<String> compareColumns2, List<String> outputColumns2, String where2,
            Map<String, DataType> columnTypes2) {
        String t1 = buildTableSource(schema1, table1, alias1, where1);
        String t2 = buildTableSource(schema2, table2, alias2, where2);
        String joinCondition = buildJoinCondition(alias1, keyColumns1, alias2, keyColumns2,
                columnTypes1, columnTypes2);
        String diffPredicate = buildDiffPredicate(alias1, alias2, compareColumns1, compareColumns2,
                columnTypes1, columnTypes2);
        String diffColumns = buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2,
                columnTypes1, columnTypes2);
        String t1KeysNull = buildKeysNullExpression(alias1, keyColumns1);
        String t2KeysNull = buildKeysNullExpression(alias2, keyColumns2);
        // NULL 键语义见 buildJoinCondition：两侧键都为 NULL 的"匹配对"按普通行比较；
        // 只有"恰好一侧键为 NULL"才是 SM/TM（另一侧行不存在）。
        String onlyT1Null = "(" + t1KeysNull + " AND NOT (" + t2KeysNull + "))";
        String onlyT2Null = "(" + t2KeysNull + " AND NOT (" + t1KeysNull + "))";
        String rowMatched = "((" + t1KeysNull + " AND " + t2KeysNull + ")"
                + " OR (NOT (" + t1KeysNull + ") AND NOT (" + t2KeysNull + ")))";

        if (capabilityProvider.supportsFeature(DatabaseFeature.FULL_OUTER_JOIN)) {
            List<String> selectExprs = new java.util.ArrayList<>();
            selectExprs.add("CASE WHEN " + onlyT1Null + " THEN 'source_missing' " +
                    "WHEN " + onlyT2Null + " THEN 'target_missing' ELSE 'mismatch' END AS nl_dq_diff_type");
            selectExprs.add("CASE WHEN " + onlyT1Null + " OR " + onlyT2Null + " THEN NULL ELSE "
                    + diffColumns + " END AS nl_dq_diff_columns1");
            selectExprs.add("CASE WHEN " + onlyT1Null + " OR " + onlyT2Null + " THEN NULL ELSE "
                    + diffColumns + " END AS nl_dq_diff_columns2");
            selectExprs.addAll(buildOutputSelect(alias1, outputColumns1, "_1"));
            selectExprs.addAll(buildOutputSelect(alias2, outputColumns2, "_2"));

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT ").append(String.join(", ", selectExprs));
            sql.append(" FROM ").append(t1).append(" FULL OUTER JOIN ").append(t2);
            sql.append(" ON ").append(joinCondition);
            sql.append(" WHERE ").append(onlyT1Null).append(" OR ").append(onlyT2Null)
                    .append(" OR (").append(rowMatched).append(" AND ").append(diffPredicate).append(")");
            sql.append(joinSettingsClause());
            return sql.toString();
        }

        String mismatchSelect = buildMismatchSelect(t1, t2, alias1, alias2,
                outputColumns1, outputColumns2, diffPredicate, joinCondition, diffColumns);
        String sourceMissingSelect = buildMissingSelect("source_missing", t2, t1, alias2, alias1,
                outputColumns2, outputColumns1, t1KeysNull, t2KeysNull, joinCondition, false);
        String targetMissingSelect = buildMissingSelect("target_missing", t1, t2, alias1, alias2,
                outputColumns1, outputColumns2, t2KeysNull, t1KeysNull, joinCondition, true);

        return "(" + mismatchSelect + ") UNION ALL (" + sourceMissingSelect + ") UNION ALL (" + targetMissingSelect + ")";
    }

    /**
     * 不带列类型的旧接口：语义与带类型版本完全一致，仅比较表达式退化为原始列引用。
     * 保留为委托，避免两份实现各自演化（join 的两条 SQL 的形状必须一致）。
     */
    @Override
    public String getJoinDiffDetailSQL(String schema1, String table1, String alias1,
            List<String> keyColumns1, List<String> compareColumns1, List<String> outputColumns1, String where1,
            String schema2, String table2, String alias2,
            List<String> keyColumns2, List<String> compareColumns2, List<String> outputColumns2, String where2) {
        return getJoinDiffDetailSQL(schema1, table1, alias1, keyColumns1, compareColumns1, outputColumns1, where1, null,
                schema2, table2, alias2, keyColumns2, compareColumns2, outputColumns2, where2, null);
    }

    private String buildTableRef(String schemaName, String tableName, String alias) {
        StringBuilder ref = new StringBuilder();
        ref.append(buildRelationRef(schemaName, tableName));
        if (alias != null && !alias.trim().isEmpty()) {
            ref.append(" ").append(alias);
        }
        return ref.toString();
    }

    protected String buildRelationRef(String schemaName, String tableName) {
        if (isSqlRelationRef(tableName)) {
            return tableName;
        }
        StringBuilder ref = new StringBuilder();
        if (schemaName != null && !schemaName.trim().isEmpty()) {
            ref.append(capabilityProvider.quote(schemaName)).append(".");
        }
        ref.append(capabilityProvider.quote(tableName));
        return ref.toString();
    }

    protected String buildSqlRelationRef(String fromSql) {
        return "(" + stripTrailingSemicolon(fromSql) + ") consilens_sql_source";
    }

    protected boolean isSqlRelationRef(String tableName) {
        return tableName != null && tableName.trim().startsWith("(");
    }

    protected String stripTrailingSemicolon(String sql) {
        String normalized = sql != null ? sql.trim() : "";
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String columnRef(String alias, String column) {
        if (alias == null || alias.trim().isEmpty()) {
            return capabilityProvider.quote(column);
        }
        return alias + "." + capabilityProvider.quote(column);
    }

    private String buildJoinCondition(String alias1, List<String> keyColumns1,
            String alias2, List<String> keyColumns2) {
        return buildJoinCondition(alias1, keyColumns1, alias2, keyColumns2, null, null);
    }

    /**
     * join 条件必须与 diff 谓词同一口径：按方言归一化（NULL→''、TRIM、类型形态）+ 二进制安全。
     * 裸列 "a.k = b.k" 有三类问题：
     *   ① 排序规则不敏感的比较（如 utf8mb4_0900_ai_ci）会把 'ABC'/'abc' 判等 → 漏报键集差异；
     *   ② 两侧键物理类型不同（typediff：NUMBER vs VARCHAR2）时依赖隐式转换；
     *   ③ NULL 键永不匹配（NULL = NULL 为 unknown）→ 两侧都存在的 NULL 键行被误判成 SM+TM。
     * 归一化表达式自带 NULL→''，③ 在归一化后自然解决；①② 由归一化 + binarySafe 解决。
     * 无类型信息时保持旧行为（裸列比较）。
     */
    private String buildJoinCondition(String alias1, List<String> keyColumns1,
            String alias2, List<String> keyColumns2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2) {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < keyColumns1.size(); i++) {
            if (i > 0) {
                condition.append(" AND ");
            }
            String col1 = keyColumns1.get(i);
            String col2 = keyColumns2.size() > i ? keyColumns2.get(i) : keyColumns1.get(i);
            DataType type1 = typeOf(columnTypes1, col1);
            DataType type2 = typeOf(columnTypes2, col2);
            if (type1 == null || type2 == null) {
                condition.append(columnRef(alias1, col1))
                        .append(" = ")
                        .append(columnRef(alias2, col2));
            } else {
                condition.append(binarySafeExpression(compareExpression(alias1, col1, type1)))
                        .append(" = ")
                        .append(binarySafeExpression(compareExpression(alias2, col2, type2)));
            }
        }
        return condition.toString();
    }

    private String buildKeysNullExpression(String alias, List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            return "1=0";
        }
        StringBuilder expr = new StringBuilder();
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                expr.append(" AND ");
            }
            expr.append(columnRef(alias, keyColumns.get(i))).append(" IS NULL");
        }
        return "(" + expr + ")";
    }

    protected String buildNullSafeNotEquals(String left, String right) {
        return "((" + left + " IS NOT NULL AND " + right + " IS NOT NULL AND " + left + " <> " + right + ")"
                + " OR (" + left + " IS NULL AND " + right + " IS NOT NULL)"
                + " OR (" + left + " IS NOT NULL AND " + right + " IS NULL))";
    }

    /**
     * Returns a join expression for concatenating strings with a separator.
     * Default implementation uses MySQL-style CONCAT_WS.
     * Subclasses for databases without CONCAT_WS (e.g., Presto, Trino) should override this.
     *
     * @param separator the separator string (e.g., ",")
     * @param args      the string expressions to join
     * @return SQL expression that joins the args with the separator
     */
    protected String stringJoin(String separator, List<String> args) {
        return "CONCAT_WS(" + separator + ", " + String.join(", ", args) + ")";
    }

    /**
     * 方言类型归一化处理器（各数据库生成器返回自己的实现）。
     *
     * <p>join 计划必须与 checksum/local 路径使用同一套归一化口径，否则同一份数据
     * 会出现"窗口内差异被报出（多报）、库内排序规则差异被吸收（漏报）"的双向偏差。
     */
    protected com.consilens.connector.api.DataTypeHandler dataTypeHandler() {
        return null;
    }

    /**
     * 二进制安全化：把归一化后的字符串转成不受列/会话排序规则影响的形式。
     *
     * <p>MySQL 族的 `utf8mb4_0900_ai_ci` 等大小写不敏感排序规则会把
     * `'ABC' = 'abc'`、`'ＡＢＣ' = 'ABC'` 判为相等，必须在比较前做字节级转换
     * （MySQL 族用 `HEX()`，PostgreSQL 用 `convert_to(...,'UTF8')`，Oracle 用
     * `STANDARD_HASH`，SQL Server 用 `CONVERT(VARBINARY(MAX), ...)`；
     * ClickHouse / Trino / Presto 字符串本身即字节序比较，保持原样）。
     *
     * <p>默认实现恒等返回：仅当方言需要字节级转换时才覆盖本方法
     * （字符串本身即字节序比较的方言保持默认即可）。
     */
    protected String binarySafeExpression(String expression) {
        return expression;
    }

    /**
     * 比较列表达式：有类型信息时套用方言归一化，否则退回原始列引用（老行为）。
     */
    protected String compareExpression(String alias, String column, DataType dataType) {
        String columnRef = columnRef(alias, column);
        com.consilens.connector.api.DataTypeHandler handler = dataTypeHandler();
        if (dataType == null || handler == null) {
            return columnRef;
        }
        // 传"已成形的列表达式"：normalizeColumn 会再次加引号，带别名的引用会被双重包裹
        return handler.normalizeExpression(columnRef, dataType);
    }

    protected DataType typeOf(Map<String, DataType> columnTypes, String column) {
        if (columnTypes == null || column == null) {
            return null;
        }
        return columnTypes.get(column);
    }

    protected String buildDiffColumnsExpression(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2) {
        return buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2, null, null);
    }

    protected String buildDiffColumnsExpression(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2) {
        if (compareColumns1 == null || compareColumns1.isEmpty()) {
            return "'[]'";
        }
        int count = Math.min(compareColumns1.size(),
                compareColumns2 != null ? compareColumns2.size() : compareColumns1.size());
        List<String> caseExprs = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            String col1 = compareColumns1.get(i);
            String col2 = compareColumns2 != null && compareColumns2.size() > i ? compareColumns2.get(i) : col1;
            String e1 = binarySafeExpression(compareExpression(alias1, col1, typeOf(columnTypes1, col1)));
            String e2 = binarySafeExpression(compareExpression(alias2, col2, typeOf(columnTypes2, col2)));
            caseExprs.add("CASE WHEN " + buildNullSafeNotEquals(e1, e2)
                    + " THEN '\"" + col1 + "\"' ELSE NULL END");
        }
        // Wrap with CONCAT('[', ..., ']') to produce a JSON-like array string
        List<String> joinArgs = new java.util.ArrayList<>();
        joinArgs.add("'['");
        joinArgs.addAll(caseExprs);
        joinArgs.add("']'");
        return stringJoin("', '", joinArgs);
    }

    protected String buildDiffPredicate(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2) {
        return buildDiffPredicate(alias1, alias2, compareColumns1, compareColumns2, null, null);
    }

    protected String buildDiffPredicate(String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2) {
        if (compareColumns1 == null || compareColumns1.isEmpty()) {
            return "1=0";
        }
        int count = Math.min(compareColumns1.size(),
                compareColumns2 != null ? compareColumns2.size() : compareColumns1.size());
        StringBuilder predicate = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                predicate.append(" OR ");
            }
            String col1 = compareColumns1.get(i);
            String col2 = compareColumns2 != null && compareColumns2.size() > i ? compareColumns2.get(i) : col1;
            String e1 = binarySafeExpression(compareExpression(alias1, col1, typeOf(columnTypes1, col1)));
            String e2 = binarySafeExpression(compareExpression(alias2, col2, typeOf(columnTypes2, col2)));
            predicate.append(buildNullSafeNotEquals(e1, e2));
        }
        return "(" + predicate + ")";
    }

    protected List<String> buildOutputSelect(String alias, List<String> outputColumns, String suffix) {
        List<String> selectExprs = new java.util.ArrayList<>();
        if (outputColumns == null) {
            return selectExprs;
        }
        for (String col : outputColumns) {
            selectExprs.add(columnRef(alias, col) + " AS " + col + suffix);
        }
        return selectExprs;
    }

    private String buildMismatchSelect(String t1, String t2, String alias1, String alias2,
            List<String> compareColumns1, List<String> compareColumns2,
            List<String> outputColumns1, List<String> outputColumns2,
            String diffPredicate, String joinCondition) {
        return buildMismatchSelect(t1, t2, alias1, alias2, outputColumns1, outputColumns2,
                diffPredicate, joinCondition,
                buildDiffColumnsExpression(alias1, alias2, compareColumns1, compareColumns2));
    }

    private String buildMismatchSelect(String t1, String t2, String alias1, String alias2,
            List<String> outputColumns1, List<String> outputColumns2,
            String diffPredicate, String joinCondition, String diffColumnsExpression) {
        List<String> selectExprs = new java.util.ArrayList<>();
        selectExprs.add("'" + "mismatch" + "' AS nl_dq_diff_type");
        selectExprs.add(diffColumnsExpression + " AS nl_dq_diff_columns1");
        selectExprs.add(diffColumnsExpression + " AS nl_dq_diff_columns2");
        selectExprs.addAll(buildOutputSelect(alias1, outputColumns1, "_1"));
        selectExprs.addAll(buildOutputSelect(alias2, outputColumns2, "_2"));

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectExprs));
        sql.append(" FROM ").append(t1).append(" INNER JOIN ").append(t2);
        sql.append(" ON ").append(joinCondition);
        sql.append(" WHERE ").append(diffPredicate);
        return sql.toString();
    }

    private String buildMissingSelect(String diffType, String primaryTable, String otherTable,
            String primaryAlias, String otherAlias,
            List<String> primaryColumns, List<String> otherColumns,
            String otherKeysNull, String primaryKeysNull, String joinCondition, boolean primaryIsTable1) {
        List<String> selectExprs = new java.util.ArrayList<>();
        selectExprs.add("'" + diffType + "' AS nl_dq_diff_type");
        selectExprs.add("NULL AS nl_dq_diff_columns1");
        selectExprs.add("NULL AS nl_dq_diff_columns2");
        if (primaryIsTable1) {
            selectExprs.addAll(buildOutputSelect(primaryAlias, primaryColumns, "_1"));
            for (String col : otherColumns) {
                selectExprs.add("NULL AS " + col + "_2");
            }
        } else {
            for (String col : otherColumns) {
                selectExprs.add("NULL AS " + col + "_1");
            }
            selectExprs.addAll(buildOutputSelect(primaryAlias, primaryColumns, "_2"));
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectExprs));
        sql.append(" FROM ").append(primaryTable).append(" LEFT OUTER JOIN ").append(otherTable);
        sql.append(" ON ").append(joinCondition);
        // 排除"匹配的 NULL 键对"：归一化 join 条件会让两侧键都为 NULL 的行正常匹配，
        // 此时不缺行（只按 diff 谓词判定），不能因键为 NULL 就算 missing。
        sql.append(" WHERE ").append(otherKeysNull).append(" AND NOT (").append(primaryKeysNull).append(")");
        return sql.toString();
    }

    private String buildTableSource(String schemaName, String tableName, String alias, String whereClause) {
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return buildTableRef(schemaName, tableName, alias);
        }
        StringBuilder sql = new StringBuilder();
        sql.append("(SELECT * FROM ");
        sql.append(buildRelationRef(schemaName, tableName));
        sql.append(" WHERE ").append(whereClause).append(") ").append(alias);
        return sql.toString();
    }

    /**
     * Build join condition for the given columns.
     */
    protected String buildJoinCondition(List<String> joinColumns) {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < joinColumns.size(); i++) {
            if (i > 0)
                condition.append(" AND ");
            condition.append("t1.").append(capabilityProvider.quote(joinColumns.get(i)))
                    .append(" = t2.").append(capabilityProvider.quote(joinColumns.get(i)));
        }
        return condition.toString();
    }

    /**
     * Build key predicate for primary key filtering.
     */
    protected String buildKeyPredicate(List<String> keyColumns, List<List<Object>> primaryKeys) {
        if (keyColumns == null || keyColumns.isEmpty() || primaryKeys == null || primaryKeys.isEmpty()) {
            return "";
        }

        if (keyColumns.size() == 1) {
            String column = capabilityProvider.quote(keyColumns.get(0));
            StringBuilder inClause = new StringBuilder();
            inClause.append(column).append(" IN (");
            String values = primaryKeys.stream()
                    .filter(pk -> pk != null && !pk.isEmpty())
                    .map(pk -> formatValue(pk.get(0)))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            inClause.append(values).append(")");
            return inClause.toString();
        }

        // Composite primary key - OR of ANDs
        StringBuilder predicate = new StringBuilder();
        for (int i = 0; i < primaryKeys.size(); i++) {
            List<Object> pk = primaryKeys.get(i);
            if (pk == null || pk.size() != keyColumns.size()) {
                continue;
            }
            if (predicate.length() > 0) {
                predicate.append(" OR ");
            }
            StringBuilder andClause = new StringBuilder();
            for (int j = 0; j < keyColumns.size(); j++) {
                if (j > 0) {
                    andClause.append(" AND ");
                }
                andClause.append(capabilityProvider.quote(keyColumns.get(j)))
                        .append(" = ")
                        .append(formatValue(pk.get(j)));
            }
            predicate.append("(").append(andClause).append(")");
        }

        return predicate.toString();
    }

    // ========== Normalization Helper Methods ==========

    /**
     * Build a COALESCE expression with a default value.
     * Helper method to reduce code duplication in normalization logic.
     *
     * @param expression   the SQL expression to wrap
     * @param defaultValue the default value if expression is NULL (e.g., "''")
     * @return COALESCE SQL expression
     */
    protected String buildCoalesceExpression(String expression, String defaultValue) {
        return "COALESCE(" + expression + ", " + defaultValue + ")";
    }

    /**
     * Build a TRIM expression to remove leading and trailing whitespace.
     * Helper method to reduce code duplication in normalization logic.
     *
     * @param expression the SQL expression to trim
     * @return TRIM SQL expression
     */
    protected String buildTrimExpression(String expression) {
        return "TRIM(" + expression + ")";
    }

    /**
     * Build a CAST expression to convert a value to a target type.
     * Helper method to reduce code duplication in normalization logic.
     *
     * @param expression the SQL expression to cast
     * @param targetType the target SQL type (e.g., "TEXT", "VARCHAR(1000)")
     * @return CAST SQL expression
     */
    protected String buildCastExpression(String expression, String targetType) {
        return "CAST(" + expression + " AS " + targetType + ")";
    }

    /**
     * Build an expression to format decimal numbers with controlled precision.
     * This is useful for normalizing floating-point values to avoid precision
     * differences.
     * Subclasses should override this method to provide database-specific
     * formatting.
     *
     * @param expression    the SQL expression representing a decimal number
     * @param decimalPlaces number of decimal places to preserve (default: 4)
     * @return SQL expression that formats the decimal number
     */
    protected String buildFormatDecimalExpression(String expression, int decimalPlaces) {
        // Default implementation using standard SQL ROUND function
        return "ROUND(" + expression + ", " + decimalPlaces + ")";
    }

    @Override
    public String getRowHashSQL(String schemaName, String tableName,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        // Default implementation - throws UnsupportedOperationException
        // Subclasses should override this method to provide database-specific implementation
        throw new UnsupportedOperationException(
                "getRowHashSQL is not implemented for " + this.getClass().getSimpleName() +
                ". Please implement this method to support row-hash local comparison.");
    }

    /**
     * Default implementation of getChecksumSQL with ChecksumAlgorithm parameter.
     * Subclasses should override this method to provide database-specific XOR implementation.
     * 
     * @param schemaName the schema name
     * @param tableName the table name
     * @param keyColumns the primary key columns
     * @param columns the columns to include in checksum
     * @param columnDataTypes mapping of column names to their data types
     * @param whereClause optional WHERE clause
     * @param checksumAlgorithm the checksum calculation algorithm
     * @return SQL checksum query
     */
    public String getChecksumSQL(String schemaName, String tableName,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause,
            ChecksumAlgorithm checksumAlgorithm) {
        
        // For XOR algorithm, throw UnsupportedOperationException if not implemented by subclass
        if (checksumAlgorithm != null && checksumAlgorithm.isXor()) {
            throw new UnsupportedOperationException(
                "XOR checksum algorithm is not implemented for " + this.getClass().getSimpleName() + 
                ". Please implement getChecksumSQL with ChecksumAlgorithm parameter or use CONCAT algorithm.");
        }
        
        // For CONCAT algorithm or null, delegate to the original method
        return getChecksumSQL(schemaName, tableName, keyColumns, columns, columnDataTypes, whereClause);
    }

    @Override
    public String getChecksumSQLFromSql(String fromSql,
            List<String> keyColumns,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause,
            ChecksumAlgorithm checksumAlgorithm) {
        return getChecksumSQL(null, buildSqlRelationRef(fromSql), keyColumns, columns,
                columnDataTypes, whereClause, checksumAlgorithm);
    }

    @Override
    public String getRowHashSQLFromSql(String fromSql,
            List<String> primaryKeys,
            List<String> columns,
            Map<String, DataType> columnDataTypes,
            String whereClause) {
        return getRowHashSQL(null, buildSqlRelationRef(fromSql), primaryKeys, columns,
                columnDataTypes, whereClause);
    }
}
