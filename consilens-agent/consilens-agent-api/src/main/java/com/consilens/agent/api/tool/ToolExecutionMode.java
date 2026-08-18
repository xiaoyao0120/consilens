package com.consilens.agent.api.tool;

/**
 * PARALLEL tools may run concurrently with other reads when they share no
 * dependency; SEQUENTIAL tools always run alone in model source order.
 */
public enum ToolExecutionMode {
    PARALLEL,
    SEQUENTIAL
}
