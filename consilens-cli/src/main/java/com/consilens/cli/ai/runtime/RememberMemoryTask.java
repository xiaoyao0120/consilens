package com.consilens.cli.ai.runtime;

import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiMemoryCandidate;

import java.util.Optional;

/**
 * Persists a non-sensitive manual memory item for the current session.
 */
public class RememberMemoryTask extends AbstractAiTask implements AiTask {

    public RememberMemoryTask(AiSessionStore sessionStore,
                              AiArtifactStore artifactStore,
                              AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.MEMORY_ADD;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        String type = context.attribute(AiRuntimeContextKeys.MEMORY_TYPE, String.class);
        String content = context.attribute(AiRuntimeContextKeys.MEMORY_CONTENT, String.class);
        if (type == null || type.isBlank() || content == null || content.isBlank()) {
            return failure(type(), "Usage: /remember <connection|project|preference> <content>");
        }
        Optional<AiMemory> stored = memoryStore.add(AiMemoryCandidate.builder()
                .type(type.trim())
                .content(content.trim())
                .source("manual:" + context.getSession().getSessionId())
                .build());
        if (stored.isEmpty()) {
            return failure(type(), "Memory rejected. Only non-sensitive content can be stored.");
        }
        updateSession(context.getSession(), builder -> builder
                .currentTask("remember")
                .status("memory_updated"));
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary("Stored memory " + stored.get().getMemoryId() + " [" + stored.get().getType() + "] "
                        + stored.get().getContent())
                .suggestedNextAction("memories")
                .build();
    }
}
