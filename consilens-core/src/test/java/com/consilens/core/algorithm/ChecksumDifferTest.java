package com.consilens.core.algorithm;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.common.enums.LocalCompareMode;
import com.consilens.core.database.adpter.DatabaseAdapter;
import com.consilens.core.database.adpter.DatabaseAdapter.RowMapper;
import com.consilens.core.database.connection.ConnectionPool;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffResult.InfoTreeNode;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.thread.ConcurrencyConfig;
import com.consilens.core.thread.ExecutorProvider;
import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.model.PoolConfiguration;
import com.consilens.core.segment.TableSegment;
import com.consilens.core.segment.TableSegment.ChecksumResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChecksumDiffer 核心算法测试")
class ChecksumDifferTest {

        @Mock
        private DatabaseAdapter mockAdapter1;

        @Mock
        private DatabaseAdapter mockAdapter2;

        @Mock
        private ExecutorProvider mockExecutorProvider;

        private TableDiffer.DifferConfig config;
        private ChecksumDiffer differ;
        private TableSegment segment1;
        private TableSegment segment2;

        @BeforeEach
        void setUp() {
                config = new TableDiffer.DifferConfig(4, 1000, false, ChecksumAlgorithm.CONCAT);
                differ = new ChecksumDiffer(config);

                // Create test table segments
                TablePath path1 = TablePath.of("test_table1");
                TablePath path2 = TablePath.of("test_table2");

                segment1 = createMockSegment(path1, mockAdapter1);
                segment2 = createMockSegment(path2, mockAdapter2);

                stubAdapterSegmentData(mockAdapter1, 1000L, "default");
                stubAdapterSegmentData(mockAdapter2, 1000L, "default");
        }

        @Nested
        @DisplayName("配置验证测试")
        class ConfigurationValidationTests {

                @Test
                @DisplayName("测试二分因子不能大于等于阈值")
                void testBisectionFactorCannotBeGreaterThanThreshold() {
                        // Given: bisectionFactor=10 >= bisectionThreshold=5 (invalid)
                        // When & Then
                        IllegalArgumentException exception = assertThrows(
                                        IllegalArgumentException.class,
                                        () -> new ChecksumDiffer(new TableDiffer.DifferConfig(10, 5, false,
                                                ChecksumAlgorithm.CONCAT)));
                        assertTrue(exception.getMessage().contains("Bisection factor must be lower than threshold"));
                }

                @Test
                @DisplayName("测试二分因子必须至少为2")
                void testBisectionFactorMustBeAtLeast2() {
                        // Given: bisectionFactor=1 < 2 (invalid)
                        // When & Then
                        IllegalArgumentException exception = assertThrows(
                                        IllegalArgumentException.class,
                                        () -> new ChecksumDiffer(new TableDiffer.DifferConfig(1, 10, false,
                                                ChecksumAlgorithm.CONCAT)));
                        assertTrue(exception.getMessage().contains("Bisection factor must be at least 2"));
                }

                @Test
                @DisplayName("测试二分阈值必须大于0")
                void testBisectionThresholdMustBePositive() {
                        IllegalArgumentException exception = assertThrows(
                                        IllegalArgumentException.class,
                                        () -> new ChecksumDiffer(new TableDiffer.DifferConfig(4, 0, false,
                                                ChecksumAlgorithm.CONCAT)));
                        assertTrue(exception.getMessage().contains("Bisection threshold must be greater than 0"));
                }

                @Test
                @DisplayName("测试有效配置创建成功")
                void testValidConfiguration() {
                        // Given: bisectionFactor=4 < bisectionThreshold=100 (valid)
                        // When & Then
                        assertDoesNotThrow(() -> new ChecksumDiffer(
                                        new TableDiffer.DifferConfig(100, 1000, false,
                                                ChecksumAlgorithm.CONCAT)));
                }
        }

        @Nested
        @DisplayName("线程池所有权测试")
        class ExecutorOwnershipTests {

                @Test
                @DisplayName("外部注入的 ExecutorProvider 不应被自动关闭")
                void shouldNotShutdownInjectedExecutorProvider() {
                        ChecksumDiffer externalDiffer = new ChecksumDiffer(config, mockExecutorProvider);

                        externalDiffer.close();

                        verify(mockExecutorProvider, never()).shutdown();
                        verify(mockExecutorProvider, never()).shutdownNow();
                }
        }

        @Nested
        @DisplayName("正常差异比较测试")
        class NormalDiffTests {

                @Test
                @DisplayName("测试相同表无差异")
                void testIdenticalTablesNoDifferences() throws Exception {
                        // Given
                        mockIdenticalTableSegments();

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
                        DiffResult result = future.get();

                        // Then
                        assertNotNull(result);
                        assertEquals(0, result.getDifferences().size());
                        assertTrue(result.getDifferences().isEmpty());
                }

                @Test
                @DisplayName("测试不同表有差异")
                void testDifferentTablesHaveDifferences() throws Exception {
                        // Given
                        mockDifferentTableSegments();

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
                        DiffResult result = future.get();

                        // Then
                        assertNotNull(result);
                        assertNotNull(result);
                        assertTrue(result.getDifferences().size() > 0);
                        verify(mockAdapter1, atLeastOnce()).countAndChecksum(any(TableSegment.class));
                        verify(mockAdapter2, atLeastOnce()).countAndChecksum(any(TableSegment.class));
                }

                @Test
                @DisplayName("测试大表分块处理")
                void testLargeTableChunkedProcessing() throws Exception {
                        // Given
                        mockLargeTableSegments();

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
                        DiffResult result = future.get(5, TimeUnit.SECONDS);

                        // Then
                        assertNotNull(result);
                        // Verify chunked queries were performed
                        verify(mockAdapter1, atLeast(2)).countAndChecksum(any(TableSegment.class));
                        verify(mockAdapter2, atLeast(2)).countAndChecksum(any(TableSegment.class));
                }

                @Test
                @DisplayName("测试 row-hash 模式能够在键被标准化后仍然取回差异行")
                void testRowHashComparisonShouldResolveRowsUsingNormalizedKeys() throws Exception {
                        differ.close();
                        differ = new ChecksumDiffer(new TableDiffer.DifferConfig(
                                        4,
                                        1000,
                                        false,
                                        ChecksumAlgorithm.CONCAT,
                                        LocalCompareMode.ROW_HASH,
                                        ConcurrencyConfig.defaultConfig()));

                        lenient().when(mockAdapter1.countAndBounds(any(TableSegment.class)))
                                        .thenReturn(new ChecksumResult(1, null, Arrays.asList(0L), Arrays.asList(1L)));
                        lenient().when(mockAdapter2.countAndBounds(any(TableSegment.class)))
                                        .thenReturn(new ChecksumResult(1, null, Arrays.asList(0L), Arrays.asList(1L)));
                        lenient().when(mockAdapter1.countAndChecksum(any(TableSegment.class)))
                                        .thenReturn(new ChecksumResult(1, "source-checksum", Arrays.asList(0L), Arrays.asList(1L)));
                        lenient().when(mockAdapter2.countAndChecksum(any(TableSegment.class)))
                                        .thenReturn(new ChecksumResult(1, "target-checksum", Arrays.asList(0L), Arrays.asList(1L)));

                        when(mockAdapter1.querySegmentRowHashes(any(TableSegment.class)))
                                        .thenReturn(Map.of(List.of(1), "source-row-hash"));
                        when(mockAdapter2.querySegmentRowHashes(any(TableSegment.class)))
                                        .thenReturn(Map.of(List.of(1), "target-row-hash"));
                        doCallRealMethod().when(mockAdapter1)
                                        .forEachSegmentRowHash(any(TableSegment.class), any());
                        doCallRealMethod().when(mockAdapter2)
                                        .forEachSegmentRowHash(any(TableSegment.class), any());

                        when(mockAdapter1.querySegmentByKeys(any(TableSegment.class), anySet()))
                                        .thenReturn(Collections.singletonList(
                                                        new Object[] {"1", "user_00001", "1380000002"}));
                        when(mockAdapter2.querySegmentByKeys(any(TableSegment.class), anySet()))
                                        .thenReturn(Collections.singletonList(
                                                        new Object[] {"1", "user_00001", "1380000001"}));

                        DiffResult result = differ.diffTables(segment1, segment2).get();

                        assertNotNull(result);
                        assertEquals(1, result.getDifferences().size());
                        assertEquals(List.of("value"), result.getDifferences().get(0).getChangedColumns1());
                }
        }

        @Nested
        @DisplayName("错误处理测试")
        class ErrorHandlingTests {

                @Test
                @DisplayName("测试数据库连接异常处理")
                void testDatabaseConnectionExceptionHandling() {
                        // Given
                        when(mockAdapter1.countAndChecksum(any(TableSegment.class)))
                                        .thenThrow(new RuntimeException("Database connection failed"));

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);

                        // Then - CompletableFuture wraps exceptions in ExecutionException
                        Exception exception = assertThrows(Exception.class, () -> future.get());
                        assertTrue(exception instanceof java.util.concurrent.ExecutionException);
                        Throwable rootCause = getRootCause(exception);
                        assertTrue(rootCause instanceof RuntimeException);
                        assertTrue(rootCause.getMessage().contains("Database connection failed"));
                }

                @Test
                @DisplayName("测试查询超时处理")
                void testQueryTimeoutHandling() {
                        // Given
                        lenient().when(mockAdapter1.countAndChecksum(any(TableSegment.class)))
                                        .thenAnswer(invocation -> {
                                                Thread.sleep(5000); // Simulate a long-running query
                                                return createSingleChecksumResult(1000);
                                        });

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);

                        // Then
                        // In practice a timeout may be needed; here we verify exception handling
                        assertThrows(Exception.class, () -> {
                                // Use a short timeout to simulate the scenario
                                future.get(1, java.util.concurrent.TimeUnit.SECONDS);
                        });
                }

                @Test
                @DisplayName("测试空表路径处理")
                void testInvalidTablePathHandling() {
                        // Given - create a segment with no key columns (an invalid configuration)
                        TableSegment invalidSegment = TableSegment.builder()
                                        .tablePath(TablePath.of("test_table"))
                                        .database(mockAdapter1)
                                        .keyColumns(Collections.emptyList()) // empty key column list
                                        .extraColumns(Arrays.asList("name", "value"))
                                        .build();

                        // When & Then - system should handle this (may return empty result or throw)
                        // Note: actual behavior depends on design; we only verify no crash
                        assertDoesNotThrow(() -> {
                                try {
                                        CompletableFuture<DiffResult> future = differ.diffTables(invalidSegment, segment2);
                                        future.get();
                                } catch (Exception e) {
                                        // Exception allowed, but must be an expected type
                                        assertTrue(e instanceof java.util.concurrent.ExecutionException ||
                                                        e instanceof IllegalArgumentException);
                                }
                        });
                }
        }

        @Nested
        @DisplayName("性能测试")
        class PerformanceTests {

                @Test
                @DisplayName("测试小表性能")
                void testSmallTablePerformance() throws Exception {
                        // Given
                        mockSmallTableSegments();

                        // When
                        long startTime = System.currentTimeMillis();
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
                        DiffResult result = future.get();
                        long duration = System.currentTimeMillis() - startTime;

                        // Then
                        assertNotNull(result);
                        assertTrue(duration < 1000, "小表比较应该在1秒内完成");
                }

                @Test
                @DisplayName("测试并发处理")
                void testConcurrentProcessing() throws Exception {
                        // Given
                        mockConcurrentTableSegments();

                        // When
                        CompletableFuture<DiffResult> future1 = differ.diffTables(segment1, segment2);
                        CompletableFuture<DiffResult> future2 = differ.diffTables(segment1, segment2);

                        // Then
                        DiffResult result1 = future1.get();
                        DiffResult result2 = future2.get();

                        assertNotNull(result1);
                        assertNotNull(result2);
                        // Verify concurrent processing does not cause race conditions
                        assertEquals(result1.getDifferences().size(), result2.getDifferences().size());
                }
        }

        @Nested
        @DisplayName("边界条件测试")
        class BoundaryConditionTests {

                @Test
                @DisplayName("测试空表处理")
                void testEmptyTableHandling() throws Exception {
                        // Given
                        mockEmptyTableSegments();

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
                        DiffResult result = future.get();

                        // Then
                        assertNotNull(result);
                        assertEquals(0, result.getDifferences().size());
                }

                @Test
                @DisplayName("测试单行表处理")
                void testSingleRowTableHandling() throws Exception {
                        // Given
                        mockSingleRowTableSegments();

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
                        DiffResult result = future.get();

                        // Then
                        assertNotNull(result);
                        // Single-row table should be handled correctly without crashing
                }

                @Test
                @DisplayName("测试极大表处理")
                void testVeryLargeTableHandling() throws Exception {
                        // Given
                        mockVeryLargeTableSegments();

                        // When
                        CompletableFuture<DiffResult> future = differ.diffTables(segment1, segment2);
                        DiffResult result = future.get();

                        // Then
                        assertNotNull(result);
                        // Verify algorithm handles large tables without crashing
                        verify(mockAdapter1, atLeast(3)).countAndChecksum(any(TableSegment.class));
                }

                @Test
                @DisplayName("测试首轮多分段后子段切换为真正二分")
                void testRecursiveSegmentsFallbackToBinarySplit() throws Exception {
                        // Given
                        mockRecursiveBinaryFallbackScenario(10_000L);

                        // When
                        DiffResult result = differ.diffTables(segment1, segment2).get();

                        // Then
                        assertNotNull(result);
                        assertTrue(result.getInfoTree().isPresent());

                        List<InfoTreeNode> standardSplitNodes = result.getInfoTree().get().getNodes().stream()
                                        .filter(node -> "segment".equals(node.getPhase()))
                                        .filter(node -> "standard_split".equals(node.getDecisionReason()))
                                        .collect(Collectors.toList());

                        assertFalse(standardSplitNodes.isEmpty(), "应该至少存在一个标准二分节点");
                        assertTrue(standardSplitNodes.stream().allMatch(node -> node.getSplitFactor() == 2),
                                        "标准分裂节点应该全部使用 factor=2");
                        assertTrue(standardSplitNodes.stream().noneMatch(node -> node.getSplitFactor() == config.getBisectionFactor()),
                                        "递归子段不应该继续沿用初始 N 分段因子");
                }
        }

        @Nested
        @DisplayName("尾部键空间覆盖测试")
        class TailKeySpaceTests {

                /**
                 * 分段基座取两侧键范围并集：较大表的键空间之外的较小表行不再依赖尾扫，
                 * 而是落入"一侧有行、另一侧无行"的段里由常规段比较报出。
                 *
                 * <p>场景：source 有 0..99（100 行），target 有 0..94 加 200/201（97 行）——
                 * target 行数更少但键范围更宽。并集基座为 [0,201]，因此 200/201 所在段上
                 * source 为空、target 有行 → SOURCE_MISSING；95..99 只存在于 source → TARGET_MISSING。
                 */
                @Test
                @DisplayName("并集分段覆盖较小表的越界行（不依赖尾扫）")
                void shouldCoverSmallerSideKeySpaceThroughUnionSegments() throws Exception {
                        List<Object[]> sourceRows = new ArrayList<>();
                        for (long i = 0; i < 100; i++) {
                                sourceRows.add(new Object[] { i, "name_" + i, "value_" + i });
                        }
                        List<Object[]> targetRows = new ArrayList<>();
                        for (long i = 0; i < 95; i++) {
                                targetRows.add(new Object[] { i, "name_" + i, "value_" + i });
                        }
                        targetRows.add(new Object[] { 200L, "name_200", "value_200" });
                        targetRows.add(new Object[] { 201L, "name_201", "value_201" });

                        lenient().when(mockAdapter1.countAndBounds(any(TableSegment.class)))
                                        .thenReturn(new ChecksumResult(100, null, Arrays.asList(0L), Arrays.asList(100L)));
                        lenient().when(mockAdapter2.countAndBounds(any(TableSegment.class)))
                                        .thenReturn(new ChecksumResult(97, null, Arrays.asList(0L), Arrays.asList(201L)));
                        stubRangeAwareData(mockAdapter1, sourceRows);
                        stubRangeAwareData(mockAdapter2, targetRows);

                        DiffResult result = differ.diffTables(segment1, segment2).get();

                        List<Long> sourceMissing = new ArrayList<>();
                        List<Long> targetMissing = new ArrayList<>();
                        for (var row : result.getDifferences()) {
                                long key = ((Number) row.getPrimaryKey().get(0)).longValue();
                                if (row.getOperation() == DiffOperation.SOURCE_MISSING) {
                                        sourceMissing.add(key);
                                } else if (row.getOperation() == DiffOperation.TARGET_MISSING) {
                                        targetMissing.add(key);
                                } else {
                                        fail("远端稀疏键的公共区间完全相同，不应出现 MISMATCH: " + row.getPrimaryKey());
                                }
                        }
                        assertEquals(List.of(200L, 201L), sourceMissing, "target 越界行应记为 source 缺失");
                        assertEquals(List.of(95L, 96L, 97L, 98L, 99L), targetMissing,
                                        "source 独有行应记为 target 缺失");
                }

                /** 让 count / countAndChecksum / querySegment 都按段范围真实取数。 */
                private void stubRangeAwareData(DatabaseAdapter adapter, List<Object[]> rows) {
                        lenient().when(adapter.count(any(TableSegment.class)))
                                        .thenAnswer(invocation -> rowsInRange(rows, invocation.getArgument(0)).size());
                        lenient().when(adapter.countAndChecksum(any(TableSegment.class)))
                                        .thenAnswer(invocation -> {
                                                List<Object[]> inRange = rowsInRange(rows, invocation.getArgument(0));
                                                StringBuilder checksum = new StringBuilder();
                                                for (Object[] row : inRange) {
                                                        checksum.append(row[0]).append(';');
                                                }
                                                return new ChecksumResult(inRange.size(), checksum.toString(),
                                                                Arrays.asList(0L), Arrays.asList(0L));
                                        });
                        lenient().when(adapter.querySegment(any(TableSegment.class)))
                                        .thenAnswer(invocation -> rowsInRange(rows, invocation.getArgument(0)));
                        lenient().when(adapter.querySegmentByKeys(any(TableSegment.class), anySet()))
                                        .thenAnswer(invocation -> {
                                                Set<List<Object>> keys = invocation.getArgument(1);
                                                return rows.stream()
                                                                .filter(row -> keys.contains(List.of(row[0])))
                                                                .collect(Collectors.toList());
                                        });
                }

                private List<Object[]> rowsInRange(List<Object[]> rows, TableSegment segment) {
                        Optional<List<Object>> minKey = segment.getMinKey();
                        Optional<List<Object>> maxKey = segment.getMaxKey();
                        return rows.stream().filter(row -> {
                                long key = ((Number) row[0]).longValue();
                                boolean aboveMin = minKey.isEmpty() || key >= ((Number) minKey.get().get(0)).longValue();
                                boolean belowMax = maxKey.isEmpty()
                                                || (segment.isUpperBoundInclusive()
                                                                ? key <= ((Number) maxKey.get().get(0)).longValue()
                                                                : key < ((Number) maxKey.get().get(0)).longValue());
                                return aboveMin && belowMax;
                        }).collect(Collectors.toList());
                }

                /**
                 * 分段边界取自较大表的键空间，较小表中落在该空间外的行不会被任何分段访问：
                 * source 有 0..99，target 与 source 公共区间完全一致，但多了 200/201 两行
                 * （target maxKey=201 > source maxKey=100）。这两行必须由尾扫补报，
                 * 且等值极值兜底不能把它们重复计数。
                 */
                @Test
                @DisplayName("尾扫把超出对端键范围的行各报一次，不重复计数")
                void shouldReportTailRowsOutsideTheOtherSidesKeyRangeExactlyOnce() throws Exception {
                        lenient().when(mockAdapter1.countAndBounds(any(TableSegment.class)))
                                        .thenAnswer(invocation -> new ChecksumResult(100, null,
                                                        Arrays.asList(0L), Arrays.asList(100L)));
                        lenient().when(mockAdapter2.countAndBounds(any(TableSegment.class)))
                                        .thenAnswer(invocation -> new ChecksumResult(99, null,
                                                        Arrays.asList(0L), Arrays.asList(201L)));
                        // 公共区间两侧完全一致：校验和相同，主流程不再产生差异
                        lenient().when(mockAdapter1.countAndChecksum(any(TableSegment.class)))
                                        .thenAnswer(invocation -> new ChecksumResult(100, "shared",
                                                        Arrays.asList(0L), Arrays.asList(100L)));
                        lenient().when(mockAdapter2.countAndChecksum(any(TableSegment.class)))
                                        .thenAnswer(invocation -> new ChecksumResult(99, "shared",
                                                        Arrays.asList(0L), Arrays.asList(100L)));
                        lenient().when(mockAdapter1.querySegment(any(TableSegment.class))).thenReturn(List.of());
                        lenient().when(mockAdapter2.querySegment(any(TableSegment.class)))
                                        .thenAnswer(invocation -> {
                                                TableSegment segment = invocation.getArgument(0);
                                                // 区间尾扫按 [source maxKey, ∞) 取数
                                                boolean tailFetch = segment.getMinKey().isPresent()
                                                                && ((Number) segment.getMinKey().get().get(0)).longValue() == 100L;
                                                if (tailFetch) {
                                                        return List.of(
                                                                        new Object[] { 200L, "name_200", "value_200" },
                                                                        new Object[] { 201L, "name_201", "value_201" });
                                                }
                                                return List.of();
                                        });
                        // 等值极值兜底：source 侧查不到这两个键，target 侧查得到
                        lenient().when(mockAdapter1.querySegmentByKeys(any(TableSegment.class), anySet()))
                                        .thenReturn(List.of());
                        lenient().when(mockAdapter2.querySegmentByKeys(any(TableSegment.class), anySet()))
                                        .thenAnswer(invocation -> {
                                                Set<List<Object>> keys = invocation.getArgument(1);
                                                return keys.stream()
                                                                .map(key -> new Object[] { key.get(0), "name_" + key.get(0),
                                                                                "value_" + key.get(0) })
                                                                .collect(Collectors.toList());
                                        });

                        DiffResult result = differ.diffTables(segment1, segment2).get();

                        // 用 list 计数（不是 set）：等值极值兜底若把同一行重复计入，总数会变成 3
                        List<Long> sourceMissingKeys = new ArrayList<>();
                        for (var row : result.getDifferences()) {
                                assertEquals(DiffOperation.SOURCE_MISSING, row.getOperation());
                                sourceMissingKeys.add(((Number) row.getPrimaryKey().get(0)).longValue());
                        }
                        assertEquals(List.of(200L, 201L), sourceMissingKeys,
                                        "两个越界行各报一次；等值极值兜底不得重复计数");
                }
        }

        // Helper methods
        private TableSegment createMockSegment(TablePath path, DatabaseAdapter adapter) {
                // Setup mock database chain for validation
                if (adapter != null) {
                        ConnectionPool mockPool = mock(
                                        ConnectionPool.class);
                        PoolConfiguration mockConfig = mock(
                                        PoolConfiguration.class);

                        lenient().when(adapter.getConnectionPool()).thenReturn(mockPool);
                        lenient().when(mockPool.getConfiguration()).thenReturn(mockConfig);
                        lenient().when(mockConfig.getJdbcUrl()).thenReturn("jdbc:mock:test");
                }

                return TableSegment.builder()
                                .tablePath(path != null ? path : TablePath.of("test_table"))
                                .database(adapter)
                                .keyColumns(Arrays.asList("id"))
                                .extraColumns(Arrays.asList("name", "value"))
                                .build();
        }

        private void mockIdenticalTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 1000L, "same");
                stubAdapterSegmentData(mockAdapter2, 1000L, "same");
                // Simulate identical table structure and data
                lenient().when(mockAdapter1.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(1000L));
                lenient().when(mockAdapter2.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(1000L));

                // Simulate identical checksums
                lenient().when(mockAdapter1.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(1000));
                lenient().when(mockAdapter2.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(1000));

        }

        private void mockDifferentTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 1000L, "source");
                stubAdapterSegmentData(mockAdapter2, 1050L, "target");
                // Simulate different row counts and checksums
                lenient().when(mockAdapter1.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(1000L));
                lenient().when(mockAdapter2.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(1050L));

                // Simulate different checksums (key: checksums must differ to trigger detection)
                lenient().when(mockAdapter1.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(1000));
                lenient().when(mockAdapter2.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(1050));

                // Use different checksum values
                lenient().when(mockAdapter1.querySegment(any(TableSegment.class))).thenReturn(createMockRows(1000));
                lenient().when(mockAdapter2.querySegment(any(TableSegment.class))).thenReturn(createMockRows(1050));
        }

        private void mockLargeTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 100000L, "large_source");
                stubAdapterSegmentData(mockAdapter2, 100100L, "large_target");
                // Simulate large table data
                lenient().when(mockAdapter1.queryForObject(contains("COUNT(*)"), eq(Long.class), any()))
                                .thenReturn(Optional.of(100000L));
                lenient().when(mockAdapter2.queryForObject(contains("COUNT(*)"), eq(Long.class), any()))
                                .thenReturn(Optional.of(100100L));

                // Simulate chunked queries returning different row counts
                lenient().when(mockAdapter1.queryForObject(contains("WHERE"), eq(Long.class), any()))
                                .thenReturn(Optional.of(25000L));
                lenient().when(mockAdapter2.queryForObject(contains("WHERE"), eq(Long.class), any()))
                                .thenReturn(Optional.of(25250L));

        }

        private void mockSmallTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 10L, "same");
                stubAdapterSegmentData(mockAdapter2, 10L, "same");
                lenient().when(mockAdapter1.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(10L));
                lenient().when(mockAdapter2.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(10L));

                lenient().when(mockAdapter1.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(10));
                lenient().when(mockAdapter2.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(10));

        }

        private void mockConcurrentTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 500L, "same");
                stubAdapterSegmentData(mockAdapter2, 500L, "same");
                // Set up reproducible mock behavior for concurrent tests
                lenient().when(mockAdapter1.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(500L));
                lenient().when(mockAdapter2.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(500L));

                lenient().when(mockAdapter1.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(500));
                lenient().when(mockAdapter2.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(500));

        }

        private void mockEmptyTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 0L, "same");
                stubAdapterSegmentData(mockAdapter2, 0L, "same");
                lenient().when(mockAdapter1.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(0L));
                lenient().when(mockAdapter2.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(0L));

                lenient().when(mockAdapter1.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) new ArrayList<>());
                lenient().when(mockAdapter2.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) new ArrayList<>());

        }

        private void mockSingleRowTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 1L, "same");
                stubAdapterSegmentData(mockAdapter2, 1L, "same");
                lenient().when(mockAdapter1.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(1L));
                lenient().when(mockAdapter2.queryForObject(anyString(), eq(Long.class), any()))
                                .thenReturn(Optional.of(1L));

                lenient().when(mockAdapter1.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(1));
                lenient().when(mockAdapter2.query(anyString(), any(RowMapper.class), any()))
                                .thenReturn((List<Object>) (List<?>) createMockChecksumResults(1));

        }

        private void mockVeryLargeTableSegments() {
                stubAdapterSegmentData(mockAdapter1, 1000000L, "huge_source");
                stubAdapterSegmentData(mockAdapter2, 1000500L, "huge_target");
                lenient().when(mockAdapter1.queryForObject(contains("COUNT(*)"), eq(Long.class), any()))
                                .thenReturn(Optional.of(1000000L));
                lenient().when(mockAdapter2.queryForObject(contains("COUNT(*)"), eq(Long.class), any()))
                                .thenReturn(Optional.of(1000500L));
                lenient().when(mockAdapter1.countAndChecksum(any(TableSegment.class)))
                                .thenAnswer(invocation -> createVeryLargeTableChecksumResult(
                                                invocation.getArgument(0),
                                                1000000L,
                                                "huge_source"));
                lenient().when(mockAdapter2.countAndChecksum(any(TableSegment.class)))
                                .thenAnswer(invocation -> createVeryLargeTableChecksumResult(
                                                invocation.getArgument(0),
                                                1000500L,
                                                "huge_target"));

                // Simulate multiple chunk splits required
                lenient().when(mockAdapter1.queryForObject(contains("WHERE"), eq(Long.class), any()))
                                .thenReturn(Optional.of(250000L));
                lenient().when(mockAdapter2.queryForObject(contains("WHERE"), eq(Long.class), any()))
                                .thenReturn(Optional.of(250125L));

        }

        private void mockRecursiveBinaryFallbackScenario(long totalRows) {
                lenient().when(mockAdapter1.countAndBounds(any(TableSegment.class)))
                                .thenAnswer(invocation -> createBoundsResult(invocation.getArgument(0), totalRows));
                lenient().when(mockAdapter2.countAndBounds(any(TableSegment.class)))
                                .thenAnswer(invocation -> createBoundsResult(invocation.getArgument(0), totalRows));

                lenient().when(mockAdapter1.countAndChecksum(any(TableSegment.class)))
                                .thenAnswer(invocation -> createRangeChecksumResult(invocation.getArgument(0), totalRows, "source"));
                lenient().when(mockAdapter2.countAndChecksum(any(TableSegment.class)))
                                .thenAnswer(invocation -> createRangeChecksumResult(invocation.getArgument(0), totalRows, "target"));

                lenient().when(mockAdapter1.querySegment(any(TableSegment.class)))
                                .thenAnswer(invocation -> createRangeRows(invocation.getArgument(0), totalRows));
                lenient().when(mockAdapter2.querySegment(any(TableSegment.class)))
                                .thenAnswer(invocation -> createRangeRows(invocation.getArgument(0), totalRows));
        }

        private List<ChecksumResult> createMockChecksumResults(int count) {
                List<ChecksumResult> results = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                        results.add(new ChecksumResult(
                                        1,
                                        "checksum_" + i,
                                        Arrays.asList(String.valueOf(i)),
                                        Arrays.asList(String.valueOf(i))));
                }
                return results;
        }

        private ChecksumResult createSingleChecksumResult(long count) {
                return createSingleChecksumResult(count, "checksum_total");
        }

        private ChecksumResult createSingleChecksumResult(long count, String checksum) {
                return new ChecksumResult(
                                count,
                                checksum,
                                Arrays.asList(0L),
                                Arrays.asList(count));
        }

        private void stubAdapterSegmentData(DatabaseAdapter adapter, long totalRows, String checksumPrefix) {
                lenient().when(adapter.countAndBounds(any(TableSegment.class)))
                                .thenAnswer(invocation -> createBoundsResult(invocation.getArgument(0), totalRows));
                lenient().when(adapter.count(any(TableSegment.class)))
                                .thenAnswer(invocation -> {
                                        TableSegment segment = invocation.getArgument(0);
                                        return Math.max(0L, getSegmentEnd(segment, totalRows) - getSegmentStart(segment));
                                });
                lenient().when(adapter.countAndChecksum(any(TableSegment.class)))
                                .thenAnswer(invocation -> createRangeChecksumResult(
                                                invocation.getArgument(0),
                                                totalRows,
                                                checksumPrefix));
                lenient().when(adapter.querySegment(any(TableSegment.class)))
                                .thenAnswer(invocation -> createRangeRows(invocation.getArgument(0), totalRows));
        }

        private Throwable getRootCause(Throwable throwable) {
                Throwable current = throwable;
                while (current.getCause() != null) {
                        current = current.getCause();
                }
                return current;
        }

        private ChecksumResult createBoundsResult(TableSegment segment, long totalRows) {
                long start = getSegmentStart(segment);
                long end = getSegmentEnd(segment, totalRows);
                return new ChecksumResult(end - start, null, Arrays.asList(start), Arrays.asList(end));
        }

        private ChecksumResult createRangeChecksumResult(TableSegment segment, long totalRows, String prefix) {
                long start = getSegmentStart(segment);
                long end = getSegmentEnd(segment, totalRows);
                long count = Math.max(0, end - start);
                return new ChecksumResult(count, prefix + "_" + start + "_" + end, Arrays.asList(start), Arrays.asList(end));
        }

        private ChecksumResult createVeryLargeTableChecksumResult(TableSegment segment, long totalRows, String prefix) {
                long start = getSegmentStart(segment);
                long end = getSegmentEnd(segment, totalRows);
                long span = Math.max(0, end - start);
                if (span < 100_000L) {
                        return new ChecksumResult(span, "huge_shared_" + start + "_" + end, Arrays.asList(start),
                                        Arrays.asList(end));
                }
                return new ChecksumResult(span, prefix + "_" + start + "_" + end, Arrays.asList(start),
                                Arrays.asList(end));
        }

        private List<Object[]> createMockRows(int count) {
                List<Object[]> rows = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                        rows.add(new Object[] { i, "name_" + i, "value_" + i });
                }
                return rows;
        }

        private List<Object[]> createRangeRows(TableSegment segment, long totalRows) {
                long start = getSegmentStart(segment);
                long end = getSegmentEnd(segment, totalRows);
                List<Object[]> rows = new ArrayList<>();
                for (long i = start; i < end; i++) {
                        rows.add(new Object[] { i, "name_" + i, "value_" + i });
                }
                return rows;
        }

        private long getSegmentStart(TableSegment segment) {
                return segment.getMinKey()
                                .filter(bounds -> !bounds.isEmpty())
                                .map(bounds -> ((Number) bounds.get(0)).longValue())
                                .orElse(0L);
        }

        private long getSegmentEnd(TableSegment segment, long totalRows) {
                return segment.getMaxKey()
                                .filter(bounds -> !bounds.isEmpty())
                                .map(bounds -> ((Number) bounds.get(0)).longValue())
                                .orElse(totalRows);
        }
}
