package com.consilens.agent.eval;

/**
 * Deterministic outcome grader: final resources/config and invariants are
 * scored first; textual quality is secondary.
 */
public interface OutcomeGrader {

    AgentGrade grade(AgentScenario scenario, AgentTrialResult trial);
}
