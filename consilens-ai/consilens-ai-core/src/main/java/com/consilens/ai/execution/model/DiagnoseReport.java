package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Structured diagnosis output suitable for repair orchestration.
 */
@Value
@Builder
public class DiagnoseReport {

    String summary;
    @Singular("pattern")
    List<String> patterns;
    @Singular("repairHint")
    List<String> repairHints;
}
