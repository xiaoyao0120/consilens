package com.consilens.ai.conversation.engine.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * Structured planner execution event for auditing and fallback visibility.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = PlannerEvent.PlannerEventBuilder.class)
public class PlannerEvent {

    String stage;
    String type;
    String message;
    @Singular("metadata")
    Map<String, String> metadata;

    @JsonPOJOBuilder(withPrefix = "")
    public static class PlannerEventBuilder {
    }
}
