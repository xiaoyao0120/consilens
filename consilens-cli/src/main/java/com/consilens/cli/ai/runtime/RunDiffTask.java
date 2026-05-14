package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.DiagnoseCapability;
import com.consilens.ai.execution.DiffCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DiagnoseReport;
import com.consilens.ai.execution.model.DiffExecutionReport;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.approval.ExecutionApprovalService;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.util.Map;

/**
 * Generates or reuses a config, then executes diff and immediate diagnosis.
 */
public class RunDiffTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;
    private final DiffCapability diffCapability;
    private final DiagnoseCapability diagnoseCapability;
    private final ExecutionApprovalService approvalService;

    public RunDiffTask(ConfigCapability configCapability,
                       DiffCapability diffCapability,
                       DiagnoseCapability diagnoseCapability,
                       ExecutionApprovalService approvalService,
                       AiSessionStore sessionStore,
                       AiArtifactStore artifactStore) {
        this(configCapability, diffCapability, diagnoseCapability, approvalService, sessionStore, artifactStore, null);
    }

    public RunDiffTask(ConfigCapability configCapability,
                       DiffCapability diffCapability,
                       DiagnoseCapability diagnoseCapability,
                       ExecutionApprovalService approvalService,
                       AiSessionStore sessionStore,
                       AiArtifactStore artifactStore,
                       AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
        this.configCapability = configCapability;
        this.diffCapability = diffCapability;
        this.diagnoseCapability = diagnoseCapability;
        this.approvalService = approvalService;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.RUN_DIFF;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        AiSession session = context.getSession();
        ConfigRef configRef = resolveConfig(context);
        if (configRef == null) {
            return failure(type(), "No config available for run. Provide generation input or reuse a session with a config.");
        }

        ValidationReport validation = configCapability.validate(configRef);
        if (!validation.isPassed()) {
            return failure(type(), "Config validation failed before run: " + joinLines(validation.getMessages()));
        }

        DryRunReport dryRun = configCapability.dryRun(configRef);
        if (!dryRun.isPassed()) {
            return failure(type(), "Dry run failed before execute: " + joinLines(dryRun.getMessages()));
        }

        ApprovalMode approvalMode = context.attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.class);
        Boolean approveExecute = context.attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, Boolean.class);
        boolean approved = approvalService.isApproved(
                session,
                "execute",
                approvalMode == null ? ApprovalMode.EXPLICIT_FLAG : approvalMode,
                Boolean.TRUE.equals(approveExecute) ? "execute" : null);
        if (!approved) {
            return AiTaskResult.builder()
                    .success(false)
                    .taskType(type())
                    .status(AiTurnResult.Status.REQUIRES_APPROVAL)
                    .summary("Diff execution requires explicit approval. Re-run with --approve-execute.")
                    .suggestedNextAction("run")
                    .build();
        }

        ArtifactRef approvalArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.APPROVAL,
                "Approved execute for session " + session.getSessionId(),
                Map.of("task", "run", "action", "execute"));

        DiffExecutionReport executionReport = diffCapability.execute(configRef);
        DiagnoseReport diagnoseReport = diagnoseCapability.diagnose(executionReport.getEvidenceRef());
        String diagnosis = renderDiagnosis(diagnoseReport);
        ArtifactRef diagnosisArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.DIAGNOSIS,
                diagnosis,
                Map.of("task", "run", "runId", executionReport.getRunId()));

        updateSession(session, builder -> builder
                .currentTask("run")
                .status(executionReport.isSuccess() ? "completed" : "failed")
                .latestApprovalId(approvalArtifact.getArtifactId())
                .latestRunArtifactId(executionReport.getResultArtifactId())
                .latestDiagnosisArtifactId(diagnosisArtifact.getArtifactId())
                .currentConfigArtifactId(configRef.getArtifactId()));
        remember("diagnosis", diagnoseReport.getSummary(), "run:" + session.getSessionId());

        return AiTaskResult.builder()
                .success(executionReport.isSuccess())
                .taskType(type())
                .status(executionReport.isSuccess() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                .summary("Run completed for session " + session.getSessionId()
                        + " run=" + executionReport.getRunId()
                        + " result=" + executionReport.getResultArtifactId()
                        + " evidence=" + executionReport.getEvidenceRef().getArtifactId()
                        + " diagnosis=" + diagnosisArtifact.getArtifactId()
                        + System.lineSeparator() + executionReport.getSummary()
                        + System.lineSeparator() + diagnosis)
                .suggestedNextAction("repair")
                .build();
    }

    private ConfigRef resolveConfig(AiTaskContext context) {
        ConfigGenerationRequest request = configRequest(context).orElse(null);
        if (request != null) {
            String originalGoal = request.getGoal();
            ConfigGenerationRequest effectiveRequest = enrichWithMemories(request, context.getSession().getSessionId());
            GeneratedConfig generated = configCapability.generate(effectiveRequest);
            ArtifactRef configArtifact = writeArtifact(
                    context.getSession().getSessionId(),
                    ArtifactType.CONFIG,
                    generated.getConfigRef().getContent(),
                    Map.of("task", "run", "goal", originalGoal == null ? "" : originalGoal));
            updateSession(context.getSession(), builder -> builder
                    .currentConfigArtifactId(configArtifact.getArtifactId())
                    .title(originalGoal));
            return ConfigRef.builder()
                    .sessionId(context.getSession().getSessionId())
                    .artifactId(configArtifact.getArtifactId())
                    .path(configArtifact.getPath())
                    .content(generated.getConfigRef().getContent())
                    .build();
        }
        return loadCurrentConfig(context.getSession()).orElse(null);
    }

    private String renderDiagnosis(DiagnoseReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("Diagnosis: ").append(report.getSummary()).append(System.lineSeparator());
        if (report.getRepairHints() != null && !report.getRepairHints().isEmpty()) {
            builder.append("Repair Hints:").append(System.lineSeparator());
            report.getRepairHints().forEach(hint -> builder.append("- ").append(hint).append(System.lineSeparator()));
        }
        return builder.toString();
    }
}
