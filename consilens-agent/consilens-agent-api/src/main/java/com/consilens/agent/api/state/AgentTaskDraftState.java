package com.consilens.agent.api.state;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

@Value
@Builder(toBuilder = true)
@Jacksonized
public class AgentTaskDraftState {
    String name;
    String description;
    String definitionId;
    String configTemplateDigest;
}
