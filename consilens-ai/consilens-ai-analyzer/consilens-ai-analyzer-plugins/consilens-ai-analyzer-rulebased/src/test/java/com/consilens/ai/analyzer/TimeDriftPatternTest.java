package com.consilens.ai.analyzer;

import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeDriftPatternTest {

    private final TimeDriftPattern pattern = new TimeDriftPattern();

    @Test
    void shouldDetectRepeatedConsistentTimestampOffset() {
        Instant start = Instant.parse("2026-08-09T00:00:00Z");
        List<DiffRow> rows = List.of(
                mismatch(1L, start, start.plusSeconds(3600)),
                mismatch(2L, start.plusSeconds(60), start.plusSeconds(3660)));

        assertTrue(pattern.detect(rows).isPresent());
    }

    @Test
    void shouldNotDiagnoseTimestampMismatchesWithoutConsistentOffset() {
        Instant start = Instant.parse("2026-08-09T00:00:00Z");
        List<DiffRow> rows = List.of(
                mismatch(1L, start, start.plusSeconds(3600)),
                mismatch(2L, start.plusSeconds(60), start.plusSeconds(7260)));

        assertFalse(pattern.detect(rows).isPresent());
    }

    private DiffRow mismatch(long id, Instant source, Instant target) {
        return DiffRow.modified(List.of(id),
                List.of(Timestamp.from(source)),
                List.of(Timestamp.from(target)),
                List.of("updated_at"),
                List.of("updated_at"));
    }
}
