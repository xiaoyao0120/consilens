package com.consilens.agent.api.state;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

@Value
@Builder
@Jacksonized
public class AgentFieldMapping {
    String sourceField;
    String targetField;
    boolean keyMapping;
}
