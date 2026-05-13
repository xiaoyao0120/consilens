package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Value;

/**
 * Reference to a canonical config artifact.
 */
@Value
@Builder
public class ConfigRef {

    String sessionId;
    String artifactId;
    String path;
    String content;
}
