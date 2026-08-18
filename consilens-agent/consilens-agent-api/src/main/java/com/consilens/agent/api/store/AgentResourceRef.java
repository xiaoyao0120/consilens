package com.consilens.agent.api.store;

import lombok.Builder;
import lombok.Value;

/**
 * Reference to a business resource created or reused by a tool or plan action.
 * IDs are always strings at the agent boundary.
 */
@Value
@Builder
public class AgentResourceRef {
    AgentResourceType resourceType;
    String resourceId;
    String name;
}
