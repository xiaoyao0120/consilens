package com.consilens.cli.ai.runtime;

import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.runtime.task.TaskRegistry;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Simple in-memory task registry.
 */
public class DefaultTaskRegistry implements TaskRegistry {

    private final Map<AiTaskType, AiTask> tasks;

    public DefaultTaskRegistry(AiTask... tasks) {
        this.tasks = Arrays.stream(tasks)
                .collect(Collectors.toMap(AiTask::type, Function.identity()));
    }

    @Override
    public Optional<AiTask> get(AiTaskType type) {
        return Optional.ofNullable(tasks.get(type));
    }
}
