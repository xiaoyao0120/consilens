package com.consilens.ai.runtime.task;

import java.util.Optional;

/**
 * Registry for AI runtime tasks.
 */
public interface TaskRegistry {

    Optional<AiTask> get(AiTaskType type);
}
