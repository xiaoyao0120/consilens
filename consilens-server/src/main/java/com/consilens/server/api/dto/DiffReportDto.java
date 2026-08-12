package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Aggregated diff report for a succeeded RUN task, derived from its latest RUN_RESULT artifact.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffReportDto {

    private String taskId;

    /** EXCELLENT | GOOD | POOR | NONE (NONE when no diff data is available). */
    private String status;

    /** 0..100 match score; null when no statistics are available. */
    private Double matchScore;

    /** Passthrough of the RUN_RESULT statistics map (may be null). */
    private Map<String, Object> statistics;

    /** Column-level change counts aggregated from the sampled diff rows. */
    private List<ColumnStat> columns;

    /** Sampled difference rows (bounded by the artifact sample size). */
    private List<SampleDiff> samples;

    /** Number of sampled rows persisted in the artifact (content.differenceSampleSize). */
    private Integer sampleSize;

    /** Total difference row count (statistics.totalDifferences). */
    private Long totalDifferenceCount;

    /** Whether the sample list was truncated (content.differenceSampleTruncated). */
    private Boolean sampleTruncated;

    /** Reserved extension point; always empty in this release. */
    private List<Object> timeline;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnStat {

        private String name;
        private Long insertCount;
        private Long updateCount;
        private Long deleteCount;
        private Long differenceCount;

        /** Share of sampled MISMATCH rows touching this column (0..100, 1 decimal). */
        private Double differencePercentage;

        /** Always true in this release: column stats are aggregated from the sample. */
        private Boolean aggregatedFromSamples;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SampleDiff {

        /** MISMATCH | SOURCE_MISSING | TARGET_MISSING. */
        private String operation;

        private List<Object> primaryKey;

        /** Raw row metadata passthrough (may contain changedColumnIndices / other keys). */
        private Map<String, Object> metadata;

        /** Merged deduplicated changed column names (changedColumns1 union changedColumns2). */
        private List<String> changedColumns;
    }
}
