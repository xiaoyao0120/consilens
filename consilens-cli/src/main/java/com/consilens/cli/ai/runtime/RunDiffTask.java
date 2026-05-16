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
import com.consilens.ai.runtime.model.AiTaskEvent;
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
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.ConnectionConfig;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Generates or reuses a config, then executes diff and immediate diagnosis.
 */
public class RunDiffTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;
    private final DiffCapability diffCapability;
    private final DiagnoseCapability diagnoseCapability;
    private final ExecutionApprovalService approvalService;
    private final ObjectMapper yamlMapper;

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
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.RUN_DIFF;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        AiSession session = context.getSession();
        List<AiTaskEvent> events = new ArrayList<>();
        ConfigRef configRef = resolveConfig(context);
        if (configRef == null) {
            return failure(type(), "No config available for run. Provide generation input or reuse a session with a config.",
                    List.of(event("load-config", "failed",
                            "No config available for run. Provide generation input or reuse a session with a config.")));
        }
        events.add(event("load-config", "completed", "Using config " + value(configRef.getArtifactId())));

        ValidationReport validation = configCapability.validate(configRef);
        ArtifactRef validationArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.VALIDATION,
                joinLines(validation.getMessages()),
                Map.of("task", commandName(context),
                        "passed", String.valueOf(validation.isPassed()),
                        "configArtifactId", value(configRef.getArtifactId())));
        events.add(event("validate", validation.isPassed() ? "completed" : "failed",
                joinLines(validation.getMessages()), validationArtifact));
        if (!validation.isPassed()) {
            return failure(type(), "Config validation failed before run."
                    + System.lineSeparator() + "validation=" + validationArtifact.getArtifactId()
                    + System.lineSeparator() + joinLines(validation.getMessages()), events);
        }

        DryRunReport dryRun = configCapability.dryRun(configRef);
        ArtifactRef dryRunArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.DRY_RUN,
                joinLines(dryRun.getMessages()),
                Map.of("task", commandName(context),
                        "passed", String.valueOf(dryRun.isPassed()),
                        "configArtifactId", value(configRef.getArtifactId()),
                        "validationArtifactId", validationArtifact.getArtifactId()));
        events.add(event("dry-run", dryRun.isPassed() ? "completed" : "failed",
                joinLines(dryRun.getMessages()), dryRunArtifact));
        if (!dryRun.isPassed()) {
            return failure(type(), "Dry run failed before execute."
                    + System.lineSeparator() + "dryRun=" + dryRunArtifact.getArtifactId()
                    + System.lineSeparator() + joinLines(dryRun.getMessages()), events);
        }

        ApprovalMode approvalMode = context.attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.class);
        Boolean approveExecute = context.attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, Boolean.class);
        String approvalPrompt = renderApprovalPrompt(configRef, validationArtifact, dryRunArtifact);
        boolean approved = approvalService.isApproved(
                session,
                "execute",
                approvalMode == null ? ApprovalMode.EXPLICIT_FLAG : approvalMode,
                Boolean.TRUE.equals(approveExecute) ? "execute" : null);
        if (!approved) {
            events.add(event("approval", "required", "Execution approval is required."));
            return AiTaskResult.builder()
                    .success(false)
                    .taskType(type())
                    .status(AiTurnResult.Status.REQUIRES_APPROVAL)
                    .summary(approvalPrompt)
                    .suggestedNextAction("approve_execute")
                    .events(events)
                    .build();
        }

        ArtifactRef approvalArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.APPROVAL,
                approvalPrompt + System.lineSeparator() + System.lineSeparator()
                        + "Approved execute for session " + session.getSessionId(),
                Map.of("task", commandName(context),
                        "action", "execute",
                        "approved", "true",
                        "configArtifactId", value(configRef.getArtifactId()),
                        "validationArtifactId", validationArtifact.getArtifactId(),
                        "dryRunArtifactId", dryRunArtifact.getArtifactId()));
        events.add(event("approval", "completed", "Execution approved.", approvalArtifact));

        DiffExecutionReport executionReport = diffCapability.execute(configRef);
        ArtifactRef resultArtifact = artifactStore.get(executionReport.getResultArtifactId()).orElse(null);
        events.add(event("diff", executionReport.isSuccess() ? "completed" : "failed",
                executionReport.getSummary(),
                executionReport.getResultArtifactId(),
                ArtifactType.DIFF_RESULT,
                resultArtifact == null ? Map.of() : resultArtifact.getMetadata()));
        DiagnoseReport diagnoseReport = diagnoseCapability.diagnose(executionReport.getEvidenceRef());
        String diagnosis = renderDiagnosis(diagnoseReport);
        ArtifactRef diagnosisArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.DIAGNOSIS,
                diagnosis,
                Map.of("task", "run",
                        "runId", value(executionReport.getRunId()),
                        "resultArtifactId", value(executionReport.getResultArtifactId()),
                        "evidenceArtifactId", executionReport.getEvidenceRef() == null
                                ? ""
                                : value(executionReport.getEvidenceRef().getArtifactId())));
        events.add(event("analyze", "completed", diagnoseReport.getSummary(), diagnosisArtifact));
        ArtifactRef runAuditArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.RUN_AUDIT,
                renderRunAudit(configRef, validationArtifact, dryRunArtifact, approvalArtifact, executionReport, diagnosisArtifact),
                Map.of("task", commandName(context),
                        "runId", value(executionReport.getRunId()),
                        "configArtifactId", value(configRef.getArtifactId()),
                        "validationArtifactId", validationArtifact.getArtifactId(),
                        "dryRunArtifactId", dryRunArtifact.getArtifactId(),
                        "approvalArtifactId", approvalArtifact.getArtifactId(),
                        "resultArtifactId", value(executionReport.getResultArtifactId()),
                        "evidenceArtifactId", executionReport.getEvidenceRef() == null
                                ? ""
                                : value(executionReport.getEvidenceRef().getArtifactId()),
                        "diagnosisArtifactId", diagnosisArtifact.getArtifactId()));
        events.add(event("audit", "completed", "Persisted run audit " + runAuditArtifact.getArtifactId(), runAuditArtifact));

        updateSession(session, builder -> builder
                .currentTask("run")
                .status(executionReport.isSuccess() ? "completed" : "failed")
                .latestApprovalId(approvalArtifact.getArtifactId())
                .latestRunArtifactId(executionReport.getResultArtifactId())
                .latestDiagnosisArtifactId(diagnosisArtifact.getArtifactId())
                .latestAuditArtifactId(runAuditArtifact.getArtifactId())
                .currentConfigArtifactId(configRef.getArtifactId()));
        remember("diagnosis", diagnoseReport.getSummary(), "run:" + session.getSessionId());

        return AiTaskResult.builder()
                .success(executionReport.isSuccess())
                .taskType(type())
                .status(executionReport.isSuccess() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                .summary(renderCompletionSummary(session, configRef, validationArtifact, dryRunArtifact,
                        executionReport, diagnosisArtifact, diagnosis))
                .suggestedNextAction("repair")
                .events(events)
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

    private String renderApprovalPrompt(ConfigRef configRef, ArtifactRef validationArtifact, ArtifactRef dryRunArtifact) {
        CliConfiguration config = parseConfig(configRef);
        StringBuilder builder = new StringBuilder();
        builder.append("About to execute diff:").append(System.lineSeparator());
        builder.append("  source: ").append(dataset(config == null ? null : config.getSource())).append(System.lineSeparator());
        builder.append("  target: ").append(dataset(config == null ? null : config.getTarget())).append(System.lineSeparator());
        builder.append("  strategy: ").append(strategy(config)).append(System.lineSeparator());
        builder.append("  result sinks: ").append(resultSinks(config)).append(System.lineSeparator());
        builder.append("  validation: passed (").append(validationArtifact.getArtifactId()).append(")").append(System.lineSeparator());
        builder.append("  dry-run: passed (").append(dryRunArtifact.getArtifactId()).append(")").append(System.lineSeparator());
        builder.append(System.lineSeparator()).append("Type: /approve execute");
        return builder.toString();
    }

    private String renderCompletionSummary(AiSession session,
                                           ConfigRef configRef,
                                           ArtifactRef validationArtifact,
                                           ArtifactRef dryRunArtifact,
                                           DiffExecutionReport executionReport,
                                           ArtifactRef diagnosisArtifact,
                                           String diagnosis) {
        CliConfiguration config = parseConfig(configRef);
        StringBuilder builder = new StringBuilder();
        builder.append("Run completed for session ").append(session.getSessionId());
        if (executionReport.getRunId() != null && !executionReport.getRunId().isBlank()) {
            builder.append(" run=").append(executionReport.getRunId());
        }
        builder.append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Execution:").append(System.lineSeparator());
        builder.append("  source: ").append(dataset(config == null ? null : config.getSource())).append(System.lineSeparator());
        builder.append("  target: ").append(dataset(config == null ? null : config.getTarget())).append(System.lineSeparator());
        builder.append("  strategy: ").append(strategy(config)).append(System.lineSeparator());
        builder.append("  result sinks: ").append(resultSinks(config)).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Stages:").append(System.lineSeparator());
        builder.append("  - validate: passed (").append(validationArtifact.getArtifactId()).append(")").append(System.lineSeparator());
        builder.append("  - dry-run: passed (").append(dryRunArtifact.getArtifactId()).append(")").append(System.lineSeparator());
        builder.append("  - diff: ").append(executionReport.isSuccess() ? "completed" : "failed").append(System.lineSeparator());
        builder.append("  - analyze: completed").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Artifacts:").append(System.lineSeparator());
        builder.append("  - result: ").append(value(executionReport.getResultArtifactId())).append(System.lineSeparator());
        builder.append("  - evidence: ").append(executionReport.getEvidenceRef() == null
                ? "(none)"
                : value(executionReport.getEvidenceRef().getArtifactId())).append(System.lineSeparator());
        builder.append("  - diagnosis: ").append(diagnosisArtifact.getArtifactId()).append(System.lineSeparator());
        if (executionReport.getSummary() != null && !executionReport.getSummary().isBlank()) {
            builder.append(System.lineSeparator()).append(executionReport.getSummary()).append(System.lineSeparator());
        } else {
            builder.append(System.lineSeparator());
        }
        builder.append(diagnosis);
        return builder.toString();
    }

    private String renderRunAudit(ConfigRef configRef,
                                  ArtifactRef validationArtifact,
                                  ArtifactRef dryRunArtifact,
                                  ArtifactRef approvalArtifact,
                                  DiffExecutionReport executionReport,
                                  ArtifactRef diagnosisArtifact) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Run Audit").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Status: ").append(executionReport.isSuccess() ? "SUCCESS" : "FAILED").append(System.lineSeparator());
        builder.append("Config: ").append(value(configRef.getArtifactId())).append(System.lineSeparator());
        builder.append("Validation: ").append(validationArtifact.getArtifactId()).append(System.lineSeparator());
        builder.append("Dry Run: ").append(dryRunArtifact.getArtifactId()).append(System.lineSeparator());
        builder.append("Approval: ").append(approvalArtifact.getArtifactId()).append(System.lineSeparator());
        builder.append("Result: ").append(value(executionReport.getResultArtifactId())).append(System.lineSeparator());
        builder.append("Evidence: ").append(executionReport.getEvidenceRef() == null
                ? "(none)"
                : value(executionReport.getEvidenceRef().getArtifactId())).append(System.lineSeparator());
        builder.append("Diagnosis: ").append(diagnosisArtifact.getArtifactId()).append(System.lineSeparator());
        if (executionReport.getRunId() != null && !executionReport.getRunId().isBlank()) {
            builder.append("Run ID: ").append(executionReport.getRunId()).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private CliConfiguration parseConfig(ConfigRef configRef) {
        if (configRef == null || configRef.getContent() == null || configRef.getContent().isBlank()) {
            return null;
        }
        try {
            return yamlMapper.readValue(configRef.getContent(), CliConfiguration.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String dataset(ConnectionConfig config) {
        if (config == null) {
            return "(unknown)";
        }
        ConnectionConfig.ResourceConfig resource = config.getResource();
        if (resource == null) {
            return value(config.getType()) + " " + value(config.getName());
        }
        String location = "sql".equalsIgnoreCase(resource.getType()) ? value(resource.getPath()) : value(resource.getName());
        return value(config.getType()) + " " + value(config.getName()) + " " + value(resource.getType()) + ":" + location;
    }

    private String strategy(CliConfiguration config) {
        if (config == null) {
            return "(unknown)";
        }
        String mode = value(config.getStrategyMode());
        String algorithm = config.getAlgorithm();
        return algorithm == null || algorithm.isBlank() ? mode : mode + "/" + algorithm;
    }

    private String resultSinks(CliConfiguration config) {
        if (config == null || config.getResult() == null || config.getResult().getSinks() == null
                || config.getResult().getSinks().isEmpty()) {
            return "(none)";
        }
        List<String> sinks = config.getResult().getSinks().stream()
                .map(this::sink)
                .collect(Collectors.toList());
        return String.join(", ", sinks);
    }

    private String sink(SinkConfig sink) {
        if (sink == null) {
            return "(unknown)";
        }
        return value(sink.getFormat()) + "/" + value(sink.getType()) + (sink.isEnabled() ? "" : " (disabled)");
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "(unknown)" : value;
    }

    private String commandName(AiTaskContext context) {
        return context.getCommand() == null || context.getCommand().getName() == null || context.getCommand().getName().isBlank()
                ? "run"
                : context.getCommand().getName();
    }
}
