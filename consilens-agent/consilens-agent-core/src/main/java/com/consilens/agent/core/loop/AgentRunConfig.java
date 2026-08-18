package com.consilens.agent.core.loop;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

@Value
@Builder
public class AgentRunConfig {
    int maxTurns;
    int maxToolCalls;
    Duration runTimeout;
    boolean parallelReadsEnabled;

    public static AgentRunConfig defaults() {
        return AgentRunConfig.builder()
                .maxTurns(8)
                .maxToolCalls(20)
                .runTimeout(Duration.ofSeconds(120))
                .parallelReadsEnabled(false)
                .build();
    }
}
