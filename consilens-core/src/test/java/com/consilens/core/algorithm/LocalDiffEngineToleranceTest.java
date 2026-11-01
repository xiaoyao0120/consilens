package com.consilens.core.algorithm;

import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.normalization.NormalizationSpecs;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDiffEngineToleranceTest {

    @Test
    void shouldTreatValuesWithinConfiguredToleranceAsEqual() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.6)), null, null);

        List<DiffRow> differences = diff(spec, 100.0, 100.5);

        assertTrue(differences.isEmpty(), "0.5 差异在 0.6 绝对容差内应判等");
    }

    @Test
    void shouldReportDifferenceBeyondConfiguredTolerance() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.1)), null, null);

        List<DiffRow> differences = diff(spec, 100.0, 100.5);

        assertEquals(1, differences.size());
        assertEquals(DiffRow.class, differences.get(0).getClass());
        assertTrue(differences.get(0).getChangedColumns1().contains("amount"));
    }

    @Test
    void shouldApplyColumnLevelToleranceOverTypeLevel() {
        NormalizationSpec typeLevel = NormalizationSpecs.fromTypeRules(
                Map.of("float", Map.of("tolerance", 0.1)), null, null);
        NormalizationSpec spec = NormalizationSpecs.withColumnRules(typeLevel,
                Map.of("amount", Map.of("tolerance", 0.6)));

        assertTrue(diff(spec, 100.0, 100.5).isEmpty());
    }

    @Test
    void shouldKeepIntegerColumnsExactEvenWithToleranceRule() {
        NormalizationSpec spec = NormalizationSpecs.fromTypeRules(
                Map.of("integer", Map.of("tolerance", 10)), null, null);

        List<DiffRow> differences = diff(spec, 100.0, 101.0);

        assertEquals(1, differences.size(), "整数列必须精确比较");
    }

    private static List<DiffRow> diff(NormalizationSpec spec, double sourceValue, double targetValue) {
        List<Object[]> rows1 = List.<Object[]>of(new Object[]{1L, sourceValue});
        List<Object[]> rows2 = List.<Object[]>of(new Object[]{1L, targetValue});
        return LocalDiffEngine.findDifferencesWithTolerance(
                rows1, rows2,
                List.of("id"), List.of("amount"),
                List.of("id"), List.of("amount"),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                Map.of("id", DataType.BIGINT, "amount", DataType.DOUBLE),
                null, null, spec);
    }
}
