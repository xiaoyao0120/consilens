package com.consilens.agent.eval;

/**
 * Runs a scenario against the loop; implementations wire the scripted model,
 * in-memory persistence and an echo/domain tool registry.
 */
public interface AgentTrialRunner {

    AgentTrialResult run(AgentScenario scenario);
}
