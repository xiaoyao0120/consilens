package com.consilens.core.diff;

import com.consilens.core.diff.DiffResult.DiffStatistics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 统计字段的边界保护：unchangedCount 不允许为负数。
 */
class DiffResultStatisticsTest {

    @Test
    void unchangedCountNeverNegativeWhenMismatchExceedsRowCount() {
        // 真实场景：mismatch(更新)统计 > 表行数时，unchanged 不允许为负
        DiffStatistics s = DiffStatistics.builder()
                .sourceRowCount(10000)
                .targetRowCount(10000)
                .mismatchCount(10006)
                .build();
        assertEquals(0, s.getUnchangedCount());
    }

    @Test
    void unchangedCountIsMinRowsMinusMismatch() {
        DiffStatistics s = DiffStatistics.builder()
                .sourceRowCount(10000)
                .targetRowCount(10000)
                .mismatchCount(100)
                .build();
        assertEquals(9900, s.getUnchangedCount());
    }

    @Test
    void unchangedCountUsesSmallerSideWhenRowCountsDiffer() {
        DiffStatistics s = DiffStatistics.builder()
                .sourceRowCount(8000)
                .targetRowCount(10000)
                .mismatchCount(500)
                .build();
        assertEquals(7500, s.getUnchangedCount());
    }
}
