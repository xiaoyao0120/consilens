package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactListDto {

    private String artifactId;
    private String artifactType;
    private String format;
    private Long sizeBytes;
    private Instant createdAt;
    private Map<String, Object> metadata;

    /** Total difference count for RUN_RESULT artifacts; null for other kinds. */
    private Long differenceCount;

    /** Rows written to the differences file; null for other kinds or legacy format. */
    private Long differenceRows;

    /** Whether the differences file was truncated at maxRows; null for other kinds. */
    private Boolean differenceTruncated;

    /** Storage URI of the differences file (jsonl); null when differences are inline/absent. */
    private String differencesUri;

    /** Parsed statistics (summary/column stats) for RUN_RESULT artifacts; null when absent. */
    private Map<String, Object> statistics;
}
