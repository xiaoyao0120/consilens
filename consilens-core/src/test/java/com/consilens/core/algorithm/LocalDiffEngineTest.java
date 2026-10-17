package com.consilens.core.algorithm;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffOperation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import com.consilens.connector.api.model.DataType;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.sql.Timestamp;

/**
 * Test for LocalDiffEngine functionality.
 */
public class LocalDiffEngineTest {

        @Test
        public void testFindDifferencesWithEmptyData() {
                List<Object[]> rows1 = Collections.emptyList();
                List<Object[]> rows2 = Collections.emptyList();
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("name");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertTrue(differences.isEmpty());
        }

        @Test
        public void testFindDifferencesWithIdenticalData() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertTrue(differences.isEmpty());
        }

        @Test
        public void testFindDifferencesWithAddedRows() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.SOURCE_MISSING, differences.get(0).getOperation());
                assertEquals(2, differences.get(0).getTargetValues().get().get(0)); // id = 2
        }

        @Test
        public void testFindDifferencesWithRemovedRows() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.TARGET_MISSING, differences.get(0).getOperation());
                assertEquals(2, differences.get(0).getSourceValues().get().get(0)); // id = 2
        }

        @Test
        public void testFindDifferencesWithUpdatedRows() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 25 },
                                new Object[] { 2, "Bob", 30 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", 26 }, // age changed
                                new Object[] { 2, "Bob", 30 });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.MISMATCH, differences.get(0).getOperation());
        }

        @Test
        public void testFindDifferencesWithComplexKey() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "A", "Alice", 25 },
                                new Object[] { 1, "B", "Alice", 25 });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "A", "Alice", 26 }, // age changed
                                new Object[] { 2, "A", "Bob", 30 } // different compound key
                );
                List<String> keyColumns = Arrays.asList("id", "type");
                List<String> extraColumns = Arrays.asList("name", "age");

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns);

                // Should detect: 1 updated row (1,A) + 1 added row (2,A) + 1 removed row (1,B)
                assertEquals(3, differences.size());
        }

        @Test
        public void testFindDifferencesWithNormalization() {
                List<Object[]> rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", "25.0000", "2023-01-01 00:00:00" });
                List<Object[]> rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "Alice", "25.0000", "2023-01-01 00:00:00" });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Arrays.asList("name", "score", "date");

                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.INTEGER);
                columnTypes.put("name", DataType.VARCHAR);
                columnTypes.put("score", DataType.DECIMAL);
                columnTypes.put("date", DataType.DATETIME);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                // LocalDiffEngine compares database-side normalized values.
                // If the rows are already canonicalized, no differences should remain.
                assertTrue(differences.isEmpty(), "Should find no differences after normalization");
        }

        @Test
        public void testTimestampDifferenceOfEightHoursIsNotIgnored() {
                List<Object[]> rows1 = Collections.singletonList(
                                new Object[] { 1, Timestamp.valueOf("2023-01-01 00:00:00") });
                List<Object[]> rows2 = Collections.singletonList(
                                new Object[] { 1, Timestamp.valueOf("2023-01-01 08:00:00") });
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("created_at");
                Map<String, DataType> columnTypes = new HashMap<>();
                columnTypes.put("id", DataType.INTEGER);
                columnTypes.put("created_at", DataType.TIMESTAMP);

                List<DiffRow> differences = LocalDiffEngine.findDifferences(
                                rows1, rows2, keyColumns, extraColumns, keyColumns, extraColumns,
                                columnTypes, columnTypes);

                assertEquals(1, differences.size());
                assertEquals(DiffOperation.MISMATCH, differences.get(0).getOperation());
        }

        @Test
        public void testSortedKeysAtShardBoundary() {
                List<String> keyColumns = Collections.singletonList("id");
                List<String> extraColumns = Collections.singletonList("name");

                // 两侧都按键升序输出，相邻分片在边界键 3 处重叠（前片尾部、后片头部各出现一次）。
                List<Object[]> shard1Rows1 = Arrays.<Object[]>asList(
                                new Object[] { 1, "a" },
                                new Object[] { 2, "b" },
                                new Object[] { 3, "c" });
                List<Object[]> shard1Rows2 = Arrays.<Object[]>asList(
                                new Object[] { 1, "a" },
                                new Object[] { 2, "b" },
                                new Object[] { 3, "c" });

                List<DiffRow> boundaryEqual = LocalDiffEngine.findDifferences(
                                shard1Rows1, shard1Rows2, keyColumns, extraColumns, keyColumns, extraColumns);
                assertTrue(boundaryEqual.isEmpty(), "边界键在两侧相同且值一致时不应产生差异");

                // 后片：边界键 3 在目标侧缺失，4 两侧相同 -> 只报 3 的 TARGET_MISSING。
                List<Object[]> shard2Rows1 = Arrays.<Object[]>asList(
                                new Object[] { 3, "c" },
                                new Object[] { 4, "d" });
                List<Object[]> shard2Rows2Missing = Collections.<Object[]>singletonList(new Object[] { 4, "d" });

                List<DiffRow> boundaryMissing = LocalDiffEngine.findDifferences(
                                shard2Rows1, shard2Rows2Missing, keyColumns, extraColumns, keyColumns, extraColumns);
                assertEquals(1, boundaryMissing.size());
                assertEquals(DiffOperation.TARGET_MISSING, boundaryMissing.get(0).getOperation());
                assertEquals(3, boundaryMissing.get(0).getPrimaryKey().get(0));

                // 边界键两侧都存在但值不同 -> MISMATCH（键相同不代表行相同）。
                List<Object[]> shard2Rows2Changed = Arrays.<Object[]>asList(
                                new Object[] { 3, "C" },
                                new Object[] { 4, "d" });
                List<DiffRow> boundaryMismatch = LocalDiffEngine.findDifferences(
                                shard2Rows1, shard2Rows2Changed, keyColumns, extraColumns, keyColumns, extraColumns);
                assertEquals(1, boundaryMismatch.size());
                assertEquals(DiffOperation.MISMATCH, boundaryMismatch.get(0).getOperation());
                assertEquals(3, boundaryMismatch.get(0).getPrimaryKey().get(0));
        }
}
