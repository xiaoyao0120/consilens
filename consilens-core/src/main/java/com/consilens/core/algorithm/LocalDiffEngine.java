package com.consilens.core.algorithm;

import com.consilens.connector.api.DataTypeHandler;
import com.consilens.connector.api.model.DataType;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Performs efficient in-memory comparison of row sets.
 */
@Slf4j
public class LocalDiffEngine {

    /**
     * Find differences between two sets of rows (backward compatibility).
     *
     * @param rows1         Rows from table 1
     * @param rows2         Rows from table 2
     * @param keyColumns1   Key column names for table 1
     * @param extraColumns1 Extra column names for table 1
     * @param keyColumns2   Key column names for table 2
     * @param extraColumns2 Extra column names for table 2
     * @return List of differences as DiffRow objects
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2) {
        // Call the new method without column types (backward compatibility)
        return findDifferences(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Find differences between two sets of rows (type-aware).
     *
     * @param rows1         Rows from table 1
     * @param rows2         Rows from table 2
     * @param keyColumns1   Key column names for table 1
     * @param extraColumns1 Extra column names for table 1
     * @param keyColumns2   Key column names for table 2
     * @param extraColumns2 Extra column names for table 2
     * @param columnTypes1  Column data types for table 1
     * @param columnTypes2  Column data types for table 2
     * @return List of differences as DiffRow objects
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2) {
        return findDifferences(rows1, rows2, keyColumns1, extraColumns1, keyColumns2, extraColumns2,
                columnTypes1, columnTypes2, null, null);
    }

    /**
     * Find differences with dialect-aware value normalization. Each side's
     * {@link DataTypeHandler} maps driver-specific representations onto the
     * common comparison form (e.g. MySQL YEAR as {@code java.sql.Date}).
     */
    public static List<DiffRow> findDifferences(
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<String> keyColumns1,
            List<String> extraColumns1,
            List<String> keyColumns2,
            List<String> extraColumns2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2) {

        log.info("Finding differences between {} and {} rows", rows1.size(), rows2.size());

        // Group rows by primary keys so we can reason about ADDED/REMOVED/MODIFIED row-by-row.
        // Keys are grouped by their *normalized* form: both sides may declare the same logical key
        // with different physical types (INT vs VARCHAR), and comparing raw values would make
        // Integer(21) and String("21") distinct keys, so every row would be reported as
        // one-sided missing.
        Map<String, PrimaryKeyGroup> rowsByPk1 = groupRowsByPrimaryKey(rows1, keyColumns1, columnTypes1, dataTypeHandler1);
        Map<String, PrimaryKeyGroup> rowsByPk2 = groupRowsByPrimaryKey(rows2, keyColumns2, columnTypes2, dataTypeHandler2);

        List<DiffRow> differences = new ArrayList<>();

        // Process keys from table1, then keys that only exist in table2, so we
        // do not need a third full-size collection for all primary keys.
        for (Map.Entry<String, PrimaryKeyGroup> entry : rowsByPk1.entrySet()) {
            PrimaryKeyGroup group1 = entry.getValue();
            PrimaryKeyGroup group2 = rowsByPk2.get(entry.getKey());
            List<Object> pk = group1.rawKey;
            List<Object[]> pkRows1 = group1.rows;
            List<Object[]> pkRows2 = group2 == null ? Collections.emptyList() : group2.rows;

            // Extract relevant columns (excluding ignored columns)
            List<Object[]> relevantRows1 = extractRelevantColumns(pkRows1, keyColumns1, extraColumns1);
            List<Object[]> relevantRows2 = extractRelevantColumns(pkRows2, keyColumns2, extraColumns2);

            // Determine differences for this primary key
            List<String> columnNames1 = new ArrayList<>(keyColumns1);
            columnNames1.addAll(extraColumns1);
            List<String> columnNames2 = new ArrayList<>(keyColumns2);
            columnNames2.addAll(extraColumns2);
            List<DiffRow> pkDifferences = findPrimaryKeyDifferences(pk, pkRows1, pkRows2, relevantRows1, relevantRows2,
                    columnNames1, columnNames2, columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2);
            differences.addAll(pkDifferences);
        }

        for (Map.Entry<String, PrimaryKeyGroup> entry : rowsByPk2.entrySet()) {
            if (rowsByPk1.containsKey(entry.getKey())) {
                continue;
            }
            PrimaryKeyGroup group2 = entry.getValue();
            List<Object[]> pkRows2 = group2.rows;
            List<Object[]> relevantRows2 = extractRelevantColumns(pkRows2, keyColumns2, extraColumns2);
            List<String> columnNames1 = new ArrayList<>(keyColumns1);
            columnNames1.addAll(extraColumns1);
            List<String> columnNames2 = new ArrayList<>(keyColumns2);
            columnNames2.addAll(extraColumns2);
            List<DiffRow> pkDifferences = findPrimaryKeyDifferences(group2.rawKey, Collections.emptyList(), pkRows2,
                    Collections.emptyList(), relevantRows2, columnNames1, columnNames2, columnTypes1, columnTypes2,
                    dataTypeHandler1, dataTypeHandler2);
            differences.addAll(pkDifferences);
        }

        log.info("Found {} total differences", differences.size());
        return differences;
    }

    /**
     * Rows sharing one logical primary key, kept together with the raw key of their own side so
     * difference rows can still report the side-native key values.
     */
    private static final class PrimaryKeyGroup {
        private final List<Object> rawKey;
        private final List<Object[]> rows = new ArrayList<>();

        private PrimaryKeyGroup(List<Object> rawKey) {
            this.rawKey = rawKey;
        }
    }

    /**
     * Group rows by their primary key values, normalized the same way the checksum path normalizes
     * keys (see {@link ValueNormalizer}), then joined on the normalized form.
     */
    private static Map<String, PrimaryKeyGroup> groupRowsByPrimaryKey(List<Object[]> rows,
            List<String> keyColumns, Map<String, DataType> columnTypes, DataTypeHandler dataTypeHandler) {
        Map<String, PrimaryKeyGroup> groupedRows = new LinkedHashMap<>();
        int keyColumnCount = keyColumns.size();

        for (Object[] row : rows) {
            String normalizedKey = normalizedKey(row, keyColumns, columnTypes, dataTypeHandler);
            PrimaryKeyGroup group = groupedRows.get(normalizedKey);
            if (group == null) {
                // Copy only the PK portion so duplicate rows with the same key compress into the same bucket.
                group = new PrimaryKeyGroup(Arrays.asList(Arrays.copyOf(row, keyColumnCount)));
                groupedRows.put(normalizedKey, group);
            }
            group.rows.add(row);
        }

        return groupedRows;
    }

    private static String normalizedKey(Object[] row, List<String> keyColumns,
            Map<String, DataType> columnTypes, DataTypeHandler dataTypeHandler) {
        StringBuilder builder = new StringBuilder();
        int keyColumnCount = keyColumns.size();
        for (int index = 0; index < keyColumnCount; index++) {
            String column = keyColumns.get(index);
            DataType dataType = columnTypes == null ? null : columnTypes.get(column);
            Object value = normalizeForComparison(row[index], dataType, dataTypeHandler);
            // 长度前缀避免键值本身含分隔符时不同复合键碰撞被并成同一组
            String text = normalizeKeyValue(value, dataType);
            builder.append(text.length()).append(':').append(text);
        }
        return builder.toString();
    }

    /**
     * 主键分量归一化。DECIMAL/NUMERIC 键不能用 ValueNormalizer 的 4 位小数形态：
     * 1.00001 与 1.00002 都会被渲染成 "1.0000"，两个不同的主键会被并进同一个分组，
     * 从而把"两侧各有一条不同键"误判成"同一键且值相同"（整体差异被吃掉）。
     * {@link ValueNormalizer#normalizeKey} 对定点键用精确十进制（去尾零）。
     */
    private static String normalizeKeyValue(Object value, DataType dataType) {
        return ValueNormalizer.normalizeKey(value, dataType == null ? DataType.UNKNOWN : dataType);
    }

    /**
     * Extract relevant columns from rows, excluding ignored columns.
     */
    private static List<Object[]> extractRelevantColumns(List<Object[]> rows, List<String> keyColumns,
            List<String> extraColumns) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        // For this implementation, assume all columns are relevant
        // In a more complex version, this would handle ignored columns
        return rows.stream()
                .map(row -> Arrays.copyOf(row, row.length))
                .collect(Collectors.toList());
    }

    /**
     * Find differences for rows with the same primary key.
     */
    private static List<DiffRow> findPrimaryKeyDifferences(
            List<Object> primaryKey,
            List<Object[]> rows1,
            List<Object[]> rows2,
            List<Object[]> relevantRows1,
            List<Object[]> relevantRows2,
            List<String> columnNames1,
            List<String> columnNames2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2) {

        List<DiffRow> differences = new ArrayList<>();

        // Cases to handle:
        // 1. Rows exist only in table 1 -> REMOVED
        // 2. Rows exist only in table 2 -> ADDED
        // 3. Rows exist in both but have different values -> MODIFIED
        // 4. Multiple rows with same PK in either table -> handle duplicates

        if (relevantRows1.isEmpty() && !relevantRows2.isEmpty()) {
            // Rows only in table 2, therefore ADDED. Iterate the *relevant* (projected) rows:
            // rows2 still contains ignored columns, so its values would not line up with
            // columnNames2. Each row needs an occurrence, otherwise storeDifferences()
            // deduplicates all rows of the key into a single diff row.
            for (int i = 0; i < relevantRows2.size(); i++) {
                DiffRow diff = DiffRow.added(primaryKey, Arrays.asList(relevantRows2.get(i)), columnNames2);
                differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                        i + 1, 0, relevantRows2.size())));
            }
        } else if (!relevantRows1.isEmpty() && relevantRows2.isEmpty()) {
            // Rows only in table 1, therefore REMOVED
            for (int i = 0; i < relevantRows1.size(); i++) {
                DiffRow diff = DiffRow.removed(primaryKey, Arrays.asList(relevantRows1.get(i)), columnNames1);
                differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                        i + 1, relevantRows1.size(), 0)));
            }
        } else if (!relevantRows1.isEmpty()) {
            // Rows exist in both tables
            if (relevantRows1.size() == 1 && relevantRows2.size() == 1) {
                // Single row in each - compare values
                Object[] row1 = relevantRows1.get(0);
                Object[] row2 = relevantRows2.get(0);
                if (!rowsEqualNormalized(row1, row2, columnNames1, columnNames2, columnTypes1, columnTypes2,
                        dataTypeHandler1, dataTypeHandler2)) {
                    // Values differ - identify which columns changed separately for each table
                    List<String> changedColumns1 = new ArrayList<>();
                    List<String> changedColumns2 = new ArrayList<>();
                    // Use columnNames1 for finding changed columns since both tables should have
                    // same structure for comparison
                    findChangedColumnsWithDetails(row1, row2, columnNames1, columnNames2, changedColumns1,
                            changedColumns2, columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2);
                    log.debug("Creating modified diff row with changed columns1: {}, columns2: {} for PK: {}",
                            changedColumns1, changedColumns2, primaryKey);
                    // Normalize values for output to ensure consistent formatting
                    List<Object> sourceValues = normalizeRowValues(row1, columnNames1, columnTypes1);
                    List<Object> targetValues = normalizeRowValues(row2, columnNames2, columnTypes2);
                    differences.add(DiffRow.modified(primaryKey, sourceValues, targetValues, columnNames1, columnNames2,
                            changedColumns1, changedColumns2));
                }
            } else {
                /*
                 * 同键多行（重复键开放档 validateUniqueKeys=false）：多重集配对。
                 * 值相等的行互相抵消（数据相同必须报 0）；剩余行两侧按"规范化整行签名"
                 * 排序后按序 1:1 配对为 MISMATCH（含 changedColumns）；配不上的行按侧报
                 * SM/TM。每行带 occurrence、键带 keyMultiplicity，让"键不唯一"可见。
                 * 与 streaming 路径共用 MultisetRowPairing，保证两条计划结论一致。
                 */
                List<String> sourceSignatures = new ArrayList<>(relevantRows1.size());
                for (Object[] row : relevantRows1) {
                    sourceSignatures.add(standardRowSignature(row, columnNames1, columnTypes1));
                }
                List<String> targetSignatures = new ArrayList<>(relevantRows2.size());
                for (Object[] row : relevantRows2) {
                    targetSignatures.add(standardRowSignature(row, columnNames2, columnTypes2));
                }
                MultisetRowPairing.Outcome outcome = MultisetRowPairing.pair(sourceSignatures, targetSignatures);
                log.info("Duplicate-key group PK={}: sourceRows={}, targetRows={}, equal={}, pairedMismatch={},"
                                + " sourceOnly={}, targetOnly={}",
                        primaryKey, relevantRows1.size(), relevantRows2.size(), outcome.equalPairs.size(),
                        outcome.mismatchPairs.size(), outcome.sourceOnly.size(), outcome.targetOnly.size());

                for (int[] pair : outcome.mismatchPairs) {
                    Object[] row1 = relevantRows1.get(pair[0]);
                    Object[] row2 = relevantRows2.get(pair[1]);
                    List<String> changedColumns1 = new ArrayList<>();
                    List<String> changedColumns2 = new ArrayList<>();
                    findChangedColumnsWithDetails(row1, row2, columnNames1, columnNames2, changedColumns1,
                            changedColumns2, columnTypes1, columnTypes2, dataTypeHandler1, dataTypeHandler2);
                    DiffRow diff = DiffRow.modified(primaryKey,
                            normalizeRowValues(row1, columnNames1, columnTypes1),
                            normalizeRowValues(row2, columnNames2, columnTypes2),
                            columnNames1, columnNames2, changedColumns1, changedColumns2);
                    differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                            outcome.sourceOccurrence[pair[0]], relevantRows1.size(), relevantRows2.size())));
                }
                for (int index : outcome.sourceOnly) {
                    DiffRow diff = DiffRow.removed(primaryKey, Arrays.asList(relevantRows1.get(index)), columnNames1);
                    differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                            outcome.sourceOccurrence[index], relevantRows1.size(), relevantRows2.size())));
                }
                for (int index : outcome.targetOnly) {
                    DiffRow diff = DiffRow.added(primaryKey, Arrays.asList(relevantRows2.get(index)), columnNames2);
                    differences.add(DiffRow.withExtraMetadata(diff, MultisetRowPairing.metadata(
                            outcome.targetOccurrence[index], relevantRows1.size(), relevantRows2.size())));
                }
            }
        }

        return differences;
    }

    /**
     * Find which columns have changed between two rows with separate tracking for
     * each table (type-aware).
     */
    private static List<Integer> findChangedColumnsWithDetails(
            Object[] row1, Object[] row2, List<String> columnNames1, List<String> columnNames2,
            List<String> changedColumns1, List<String> changedColumns2,
            Map<String, DataType> columnTypes1, Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1, DataTypeHandler dataTypeHandler2) {

        List<Integer> changedColumnIndices = new ArrayList<>();

        log.debug("Finding changed columns between rows with lengths: {} and {}", row1.length, row2.length);

        if (row1.length != row2.length) {
            // If rows have different lengths, consider all columns as changed
            log.debug("Rows have different lengths, marking all columns as changed");
            for (int i = 0; i < Math.max(row1.length, row2.length); i++) {
                changedColumnIndices.add(i);
                if (i < row1.length && columnNames1 != null && i < columnNames1.size()) {
                    changedColumns1.add(columnNames1.get(i));
                }
                if (i < row2.length && columnNames2 != null && i < columnNames2.size()) {
                    changedColumns2.add(columnNames2.get(i));
                }
            }
            return changedColumnIndices;
        }

        for (int i = 0; i < row1.length; i++) {
            String columnName1 = (columnNames1 != null && i < columnNames1.size()) ? columnNames1.get(i) : null;
            String columnName2 = (columnNames2 != null && i < columnNames2.size()) ? columnNames2.get(i) : null;
            DataType dataType1 = columnTypes1 != null && columnName1 != null ? columnTypes1.get(columnName1) : null;
            DataType dataType2 = columnTypes2 != null && columnName2 != null ? columnTypes2.get(columnName2) : null;
            Object val1 = normalizeForComparison(row1[i], dataType1, dataTypeHandler1);
            Object val2 = normalizeForComparison(row2[i], dataType2, dataTypeHandler2);

            // CRITICAL: In NONE mode, rows contain database-normalized values (strings)
            // Direct string comparison is sufficient - no need for ValueNormalizer
            // This ensures consistency with database-side normalization
            if (!valuesEqual(val1, val2, preferExactType(dataType1, dataType2))) {
                changedColumnIndices.add(i);
                if (columnName1 != null) {
                    changedColumns1.add(columnName1);
                }
                if (columnName2 != null) {
                    changedColumns2.add(columnName2);
                }
                log.trace("Column {} values differ: '{}' vs '{}'", columnName1 != null ? columnName1 : i, val1, val2);
            }
        }

        log.debug("Found {} changed columns: {}", changedColumnIndices.size(), changedColumnIndices);
        return changedColumnIndices;
    }

    /**
     * Compare two values with special handling for different types.
     */
    private static boolean valuesEqual(Object val1, Object val2) {
        return valuesEqual(val1, val2, null);
    }

    private static boolean valuesEqual(Object val1, Object val2, DataType dataType) {
        boolean result = compareValuesInternal(val1, val2, dataType);
        log.trace("Comparing values: '{}' vs '{}' = {}", val1, val2, result);
        return result;
    }

    /**
     * Internal method to compare two values with special handling for different
     * types.
     */
    private static boolean compareValuesInternal(Object val1, Object val2, DataType dataType) {
        // Handle null cases
        if (val1 == null && val2 == null) {
            return true;
        }
        if (val1 == null || val2 == null) {
            return false;
        }

        // 定点数/整数必须精确比较：原来的相对容差（1e-6）会把大数的小数差异
        // （如 1000000.1 vs 1000000.2）判成相等，属于静默漏报。
        // 只有浮点类型保留容差，用于吸收不同引擎对同一 DOUBLE 的二进制表示差异。
        if (val1 instanceof Number && val2 instanceof Number) {
            if (requiresExactNumericComparison(dataType)) {
                return compareNumbersExactly((Number) val1, (Number) val2);
            }
            return compareNumericValues((Number) val1, (Number) val2);
        }

        // Handle string comparison with normalization
        if (val1 instanceof String || val2 instanceof String) {
            String str1 = normalizeStringValue(val1);
            String str2 = normalizeStringValue(val2);
            return str1.equals(str2);
        }

        // Handle temporal types
        if (val1 instanceof java.time.Instant && val2 instanceof java.time.Instant) {
            return val1.equals(val2);
        }

        if (val1 instanceof java.sql.Timestamp && val2 instanceof java.sql.Timestamp) {
            long time1 = ((java.sql.Timestamp) val1).getTime();
            long time2 = ((java.sql.Timestamp) val2).getTime();
            return time1 == time2;
        }

        if (val1 instanceof java.util.Date && val2 instanceof java.util.Date) {
            long time1 = ((java.util.Date) val1).getTime();
            long time2 = ((java.util.Date) val2).getTime();
            return time1 == time2;
        }

        // Handle boolean types
        if (val1 instanceof Boolean && val2 instanceof Boolean) {
            return val1.equals(val2);
        }

        // Handle binary data
        if (val1 instanceof byte[] && val2 instanceof byte[]) {
            return java.util.Arrays.equals((byte[]) val1, (byte[]) val2);
        }

        // Default comparison
        return Objects.equals(val1, val2);
    }

    private static Object normalizeForComparison(Object value, DataType dataType,
            DataTypeHandler dataTypeHandler) {
        if (dataTypeHandler == null) {
            return value;
        }
        return dataTypeHandler.normalizeValueForComparison(value,
                dataType != null ? dataType : DataType.UNKNOWN);
    }

    /**
     * Compare numeric values with appropriate tolerance.
     */
    /** 两侧类型不一致时优先选"需要精确比较"的那一侧（例如 source INT / target DECIMAL）。 */
    private static DataType preferExactType(DataType left, DataType right) {
        if (requiresExactNumericComparison(left)) {
            return left;
        }
        if (requiresExactNumericComparison(right)) {
            return right;
        }
        return left != null ? left : right;
    }

    private static boolean requiresExactNumericComparison(DataType dataType) {
        if (dataType == null) {
            return false;
        }
        switch (dataType) {
            case DECIMAL:
            case NUMERIC:
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
                return true;
            default:
                return false;
        }
    }

    private static boolean compareNumbersExactly(Number num1, Number num2) {
        try {
            return toBigDecimal(num1).compareTo(toBigDecimal(num2)) == 0;
        } catch (NumberFormatException e) {
            // 非有限浮点（NaN/Infinity）落到近似比较，避免误判
            return compareNumericValues(num1, num2);
        }
    }

    private static java.math.BigDecimal toBigDecimal(Number number) {
        if (number instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) number;
        }
        if (number instanceof java.math.BigInteger) {
            return new java.math.BigDecimal((java.math.BigInteger) number);
        }
        if (number instanceof Long || number instanceof Integer
                || number instanceof Short || number instanceof Byte) {
            return java.math.BigDecimal.valueOf(number.longValue());
        }
        return new java.math.BigDecimal(number.toString());
    }

    private static boolean compareNumericValues(Number num1, Number num2) {
        // Handle integer types exactly
        if (num1 instanceof Long || num2 instanceof Long ||
                num1 instanceof Integer || num2 instanceof Integer ||
                num1 instanceof Short || num2 instanceof Short ||
                num1 instanceof Byte || num2 instanceof Byte) {
            return num1.longValue() == num2.longValue();
        }

        // Handle floating point types with tolerance
        double d1 = num1.doubleValue();
        double d2 = num2.doubleValue();

        // Use relative tolerance for floating point comparison
        // Tolerance of 1e-6 allows for typical decimal precision differences (up to
        // 0.0001% relative error)
        // This accommodates differences like 24970.810011 vs 24970.81 (difference of
        // 0.000011)
        double tolerance = 1e-6;
        double diff = Math.abs(d1 - d2);
        double scale = Math.max(Math.max(Math.abs(d1), Math.abs(d2)), 1.0);

        return diff / scale <= tolerance;
    }

    /**
     * Normalize string values for consistent comparison.
     */
    private static String normalizeStringValue(Object value) {
        if (value == null) {
            return "";
        }

        String str = value.toString().trim();

        // JSON 值的紧凑规范化：不同引擎渲染同一 JSON 的空格策略不同
        // （mysql/{“a”: 1} vs trino json_format/{“a”:1}），语义等价的 JSON
        // 必须比较紧凑形式。只在 JSON 结构可解析时应用。
        if (str.length() >= 2 && ((str.startsWith("{") && str.endsWith("}"))
                || (str.startsWith("[") && str.endsWith("]")))) {
            String compact = compactJson(str);
            if (compact != null) {
                return compact;
            }
        }

        return str;
    }

    /**
     * 语义等价的 JSON 必须比较规范形：键排序 + 紧凑分隔符。
     * 直接复用 {@link ValueNormalizer#canonicalJson(String)}——此前这里用
     * {@code readTree} + {@code writeValueAsString}，只压空格不排键序，
     * 导致 {"a":1,"b":2} 与 {"b":2,"a":1} 被误报为 MISMATCH。
     */
    private static String compactJson(String str) {
        try {
            return ValueNormalizer.canonicalJson(str);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalize all values in a row for consistent output formatting.
     * CRITICAL: In NONE mode, rows already contain database-normalized values (strings).
     * This method now simply returns the values as-is, since normalization
     * has already been done at the database level.
     *
     * @param row         The row to normalize
     * @param columnNames Column names
     * @param columnTypes Column data types
     * @return List of values (already normalized by database)
     */
    /**
     * 同键多行配对用的整行签名。必须按类型归一化后再拼：两侧对同一个 DECIMAL 可能给出
     * 不同 scale（25.0000 vs 25）或不同 JDBC 形态（BigDecimal vs String），直接 toString
     * 会让"数据相同"的两行签名不同，配对后误报成 MISMATCH。
     */
    private static String standardRowSignature(Object[] row, List<String> columnNames,
            Map<String, DataType> columnTypes) {
        List<String> normalized = new ArrayList<>(row.length);
        for (int i = 0; i < row.length; i++) {
            String columnName = columnNames != null && i < columnNames.size() ? columnNames.get(i) : null;
            DataType dataType = columnTypes != null && columnName != null ? columnTypes.get(columnName) : null;
            normalized.add(ValueNormalizer.normalizeValue(row[i],
                    dataType == null ? DataType.UNKNOWN : dataType));
        }
        return MultisetRowPairing.signature(normalized);
    }

    private static List<Object> normalizeRowValues(
            Object[] row,
            List<String> columnNames,
            Map<String, DataType> columnTypes) {

        // CRITICAL: Rows already contain database-normalized values
        // No need for additional normalization with ValueNormalizer
        List<Object> normalizedValues = new ArrayList<>(row.length);
        for (Object value : row) {
            normalizedValues.add(value);
        }
        return normalizedValues;
    }

    /**
     * Compare two rows with type-aware normalization.
     * CRITICAL: In NONE mode, rows already contain database-normalized values (strings).
     * Direct comparison is sufficient - no need for ValueNormalizer.
     *
     * @param row1         First row
     * @param row2         Second row
     * @param columnNames  Column names (used to match column types)
     * @param columnTypes1 Column data types for table 1
     * @param columnTypes2 Column data types for table 2
     * @return true if rows are equal after normalization
     */
    private static boolean rowsEqualNormalized(
            Object[] row1,
            Object[] row2,
            List<String> columnNames1,
            List<String> columnNames2,
            Map<String, DataType> columnTypes1,
            Map<String, DataType> columnTypes2,
            DataTypeHandler dataTypeHandler1,
            DataTypeHandler dataTypeHandler2) {

        if (row1.length != row2.length) {
            return false;
        }

        // CRITICAL: Rows already contain database-normalized values
        // Direct comparison is sufficient
        for (int i = 0; i < row1.length; i++) {
            String columnName1 = i < columnNames1.size() ? columnNames1.get(i) : null;
            String columnName2 = i < columnNames2.size() ? columnNames2.get(i) : null;
            DataType dataType1 = columnTypes1 != null && columnName1 != null ? columnTypes1.get(columnName1) : null;
            DataType dataType2 = columnTypes2 != null && columnName2 != null ? columnTypes2.get(columnName2) : null;
            Object val1 = normalizeForComparison(row1[i], dataType1, dataTypeHandler1);
            Object val2 = normalizeForComparison(row2[i], dataType2, dataTypeHandler2);

            if (!valuesEqual(val1, val2, preferExactType(dataType1, dataType2))) {
                String logColumn = columnName1 != null ? columnName1 : String.valueOf(i);
                log.trace("Column {} values differ: '{}' vs '{}'", logColumn, val1, val2);
                return false;
            }
        }

        return true;
    }
}
