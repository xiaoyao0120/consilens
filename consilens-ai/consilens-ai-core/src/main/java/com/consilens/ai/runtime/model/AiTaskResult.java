package com.consilens.ai.runtime.model;

import com.consilens.ai.runtime.task.AiTaskType;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Result returned by a runtime task execution.
 */
@Value
@Builder
public class AiTaskResult {

    boolean success;
    AiTaskType taskType;
    AiTurnResult.Status status;
    String summary;
    String suggestedNextAction;
    List<AiTaskEvent> events;
}
