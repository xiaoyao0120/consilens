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
 * Lightweight doctor task for runtime/session introspection.
 */
public class DoctorTask extends AbstractAiTask implements AiTask {

    public DoctorTask(AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        this(sessionStore, artifactStore, null);
    }

    public DoctorTask(AiSessionStore sessionStore, AiArtifactStore artifactStore, AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.DOCTOR;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        int artifacts = artifactStore.list(context.getSession().getSessionId()).size();
        String summary = "Session " + context.getSession().getSessionId()
                + " status=" + context.getSession().getStatus()
                + " currentTask=" + context.getSession().getCurrentTask()
                + " artifacts=" + artifacts;
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary(summary)
                .suggestedNextAction("plan")
                .build();
    }
}
