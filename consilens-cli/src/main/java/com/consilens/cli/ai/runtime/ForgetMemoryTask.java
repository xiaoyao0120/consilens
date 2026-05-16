package com.consilens.cli.ai.runtime;

import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;

/**
 * Removes a persisted memory item by id.
 */
public class ForgetMemoryTask extends AbstractAiTask implements AiTask {

    public ForgetMemoryTask(AiSessionStore sessionStore,
                            AiArtifactStore artifactStore,
                            AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.MEMORY_REMOVE;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        String memoryId = context.attribute(AiRuntimeContextKeys.MEMORY_ID, String.class);
        if (memoryId == null || memoryId.isBlank()) {
            return failure(type(), "Usage: /forget <memory-id>");
        }
        memoryStore.remove(memoryId.trim());
        updateSession(context.getSession(), builder -> builder
                .currentTask("forget")
                .status("memory_updated"));
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary("Removed memory " + memoryId.trim())
                .suggestedNextAction("memories")
                .build();
    }
}
