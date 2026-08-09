package com.consilens.ai.execution.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Request to generate a canonical config artifact.
 */
@Value
@Builder
@JsonDeserialize(builder = ConfigGenerationRequest.ConfigGenerationRequestBuilder.class)
public class ConfigGenerationRequest {

    String sessionId;
    String goal;
    @Singular("hint")
    List<String> hints;

    @JsonPOJOBuilder(withPrefix = "")
    public static class ConfigGenerationRequestBuilder {
    }
}
