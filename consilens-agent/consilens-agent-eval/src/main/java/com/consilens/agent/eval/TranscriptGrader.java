package com.consilens.agent.eval;

/**
 * Subjective rubric grader for final natural-language quality only.
 */
public interface TranscriptGrader {

    AgentGrade grade(String transcript);
}
