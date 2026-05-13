package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Validation output for a config artifact.
 */
@Value
@Builder
public class ValidationReport {

    boolean passed;
    @Singular("message")
    List<String> messages;
}
