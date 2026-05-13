package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Request to generate a canonical config artifact.
 */
@Value
@Builder
public class ConfigGenerationRequest {

    String sessionId;
    String goal;
    @Singular("hint")
    List<String> hints;
}
