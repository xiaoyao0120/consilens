package com.consilens.ai.conversation.engine.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Structured result produced by the planner before conversion to runtime actions.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = PlannerResult.PlannerResultBuilder.class)
public class PlannerResult {

    PlannerType type;
    PlannerRoute route;
    String normalizedGoal;
    @Singular("slot")
    Map<String, Object> extractedSlots;
    @Singular("missingSlot")
    List<String> missingSlots;
    @Singular("assumption")
    List<String> assumptions;
    String question;
    String answer;
    String reasoning;
    boolean fallback;
    @Singular("event")
    List<PlannerEvent> events;

    @JsonPOJOBuilder(withPrefix = "")
    public static class PlannerResultBuilder {
    }
}
