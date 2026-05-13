package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Dry-run report for a config artifact.
 */
@Value
@Builder
public class DryRunReport {

    boolean passed;
    @Singular("message")
    List<String> messages;
}
