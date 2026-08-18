package com.consilens.agent.eval;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AgentGrade {
    double score;
    boolean passed;
    List<String> reasons;
}
