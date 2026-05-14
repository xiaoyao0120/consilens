package com.consilens.cli.ai.runtime;

import com.consilens.ai.runtime.intent.DefaultIntentRouter;
import com.consilens.ai.runtime.orchestrator.AiConversationOrchestrator;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.cli.ai.DefaultConfigCapability;
import com.consilens.cli.ai.DefaultDiagnoseCapability;
import com.consilens.cli.ai.DefaultDiffCapability;

/**
 * Assembles the CLI-backed AI runtime.
 */
public class CliAiRuntimeFactory {

    public AiConversationRuntime create() {
        AiRuntimePaths paths = new AiRuntimePaths();
        AiSessionStore sessionStore = new FileAiSessionStore(paths);
        AiArtifactStore artifactStore = new FileAiArtifactStore(paths);
        AiMemoryStore memoryStore = new FileAiMemoryStore(paths);
        DefaultConfigCapability configCapability = new DefaultConfigCapability();
        DefaultDiffCapability diffCapability = new DefaultDiffCapability(artifactStore, paths);
        DefaultDiagnoseCapability diagnoseCapability = new DefaultDiagnoseCapability(null);
        DefaultTaskRegistry taskRegistry = new DefaultTaskRegistry(
                new PlanConfigTask(configCapability, sessionStore, artifactStore, memoryStore),
                new RunDiffTask(configCapability, diffCapability, diagnoseCapability, new DefaultExecutionApprovalService(),
                        sessionStore, artifactStore, memoryStore),
                new DiagnoseTask(diagnoseCapability, sessionStore, artifactStore, memoryStore),
                new RepairTask(configCapability, sessionStore, artifactStore, memoryStore),
                new ExplainTask(configCapability, sessionStore, artifactStore, memoryStore),
                new DoctorTask(sessionStore, artifactStore, memoryStore)
        );
        return new AiConversationOrchestrator(new DefaultIntentRouter(), taskRegistry, sessionStore);
    }
}
