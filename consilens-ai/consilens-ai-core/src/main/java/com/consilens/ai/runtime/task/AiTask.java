package com.consilens.ai.runtime.task;

import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;

/**
 * Unit of executable AI work.
 */
public interface AiTask {

    AiTaskType type();

    AiTaskResult execute(AiTaskContext context);
}
