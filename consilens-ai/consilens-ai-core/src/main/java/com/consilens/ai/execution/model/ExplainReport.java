package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Value;

/**
 * Human-readable explanation for a config artifact.
 */
@Value
@Builder
public class ExplainReport {

    String markdown;
}
