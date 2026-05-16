package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Jackson-based parser for planner JSON responses.
 */
public class JacksonPlannerResultParser implements PlannerResultParser {

    private final ObjectMapper objectMapper;

    public JacksonPlannerResultParser() {
        this(new ObjectMapper());
    }

    JacksonPlannerResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PlannerResult parse(String payload) {
        try {
            return objectMapper.readValue(payload, PlannerResult.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid planner JSON: " + e.getMessage(), e);
        }
    }
}
