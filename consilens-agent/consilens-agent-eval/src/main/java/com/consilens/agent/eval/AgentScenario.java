package com.consilens.agent.eval;

import com.consilens.agent.api.model.AgentModelResponse;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * One eval scenario: a scripted model transcript plus the deterministic
 * expectations the outcome grader checks.
 */
@Value
@Builder
public class AgentScenario {
    String id;
    String description;
    List<AgentModelResponse> script;
    String expectedSessionStatus;
    List<String> requiredToolNames;
    List<String> forbiddenToolNames;
    boolean expectFailure;
}
