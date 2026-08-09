package com.consilens.ai.analyzer;

import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.DiffPattern;
import com.consilens.core.diff.DiffOperation;
import com.consilens.core.diff.DiffRow;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects time drift patterns where timestamps differ by a consistent offset.
 */
public class TimeDriftPattern implements DiffPattern {

    @Override
    public String getName() {
        return "TIME_DRIFT";
    }

    @Override
    public String getDescription() {
        return "Detects consistent timestamp differences that indicate time zone or clock drift issues";
    }

    @Override
    public Optional<PatternMatch> detect(List<DiffRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }

        List<TimeDriftObservation> observations = rows.stream()
                .filter(r -> r.getOperation() == DiffOperation.MISMATCH)
                .flatMap(row -> timeDriftObservations(row).stream())
                .collect(Collectors.toList());
        if (observations.size() < 2) {
            return Optional.empty();
        }
        Set<Long> offsets = observations.stream()
                .map(TimeDriftObservation::getOffsetMillis)
                .collect(Collectors.toSet());
        if (offsets.size() != 1) {
            return Optional.empty();
        }
        long timeDriftCount = observations.stream()
                .map(TimeDriftObservation::getPrimaryKey)
                .distinct()
                .count();
        if (timeDriftCount < 2) {
            return Optional.empty();
        }
        List<String> affectedCols = observations.stream()
                .map(TimeDriftObservation::getColumnName)
                .distinct()
                .collect(Collectors.toList());
        double confidence = Math.min(0.9, 0.6 + (timeDriftCount * 0.05));

        return Optional.of(PatternMatch.builder()
                .patternName(getName())
                .patternType("TIME_DRIFT")
                .description("Detected " + timeDriftCount + " rows with a consistent timestamp offset of "
                        + offsets.iterator().next() + "ms")
                .affectedRows((int) timeDriftCount)
                .confidence(confidence)
                .affectedColumns(affectedCols)
                .repairHint("Check time zone settings and synchronize clocks between source and target databases")
                .build());
    }

    private List<TimeDriftObservation> timeDriftObservations(DiffRow row) {
        List<TimeDriftObservation> observations = new ArrayList<>();
        List<Object> sourceValues = row.getAllSourceValues();
        List<Object> targetValues = row.getAllTargetValues();
        List<String> columns = row.getColumnNames1();
        int commonSize = Math.min(columns.size(), Math.min(sourceValues.size(), targetValues.size()));
        for (int index = 0; index < commonSize; index++) {
            if (!isTimestampColumn(columns.get(index))) {
                continue;
            }
            Optional<Instant> source = toInstant(sourceValues.get(index));
            Optional<Instant> target = toInstant(targetValues.get(index));
            if (source.isEmpty() || target.isEmpty()) {
                continue;
            }
            long offsetMillis = target.get().toEpochMilli() - source.get().toEpochMilli();
            if (offsetMillis != 0) {
                observations.add(new TimeDriftObservation(row.getPrimaryKey(), columns.get(index), offsetMillis));
            }
        }
        return observations;
    }

    private boolean isTimestampColumn(String columnName) {
        String normalized = columnName == null ? "" : columnName.toLowerCase();
        return normalized.contains("time") || normalized.contains("date") || normalized.contains("ts")
                || normalized.contains("created") || normalized.contains("updated");
    }

    private Optional<Instant> toInstant(Object value) {
        if (value instanceof Timestamp) {
            return Optional.of(((Timestamp) value).toInstant());
        }
        if (value instanceof java.util.Date) {
            return Optional.of(((java.util.Date) value).toInstant());
        }
        if (value instanceof Instant) {
            return Optional.of((Instant) value);
        }
        if (value instanceof OffsetDateTime) {
            return Optional.of(((OffsetDateTime) value).toInstant());
        }
        if (value instanceof LocalDateTime) {
            return Optional.of(((LocalDateTime) value).toInstant(ZoneOffset.UTC));
        }
        return Optional.empty();
    }

    private static class TimeDriftObservation {
        private final List<Object> primaryKey;
        private final String columnName;
        private final long offsetMillis;

        private TimeDriftObservation(List<Object> primaryKey, String columnName, long offsetMillis) {
            this.primaryKey = primaryKey;
            this.columnName = columnName;
            this.offsetMillis = offsetMillis;
        }

        private List<Object> getPrimaryKey() {
            return primaryKey;
        }

        private String getColumnName() {
            return columnName;
        }

        private long getOffsetMillis() {
            return offsetMillis;
        }
    }
}
