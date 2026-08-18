package com.consilens.agent.api.policy;

import lombok.Builder;
import lombok.Value;

/**
 * First-version actor identity is the API key fingerprint or "local"; the
 * column exists so a real identity system can replace it without schema
 * migration.
 */
@Value
@Builder
public class AgentActor {
    String id;
    String displayName;
}
