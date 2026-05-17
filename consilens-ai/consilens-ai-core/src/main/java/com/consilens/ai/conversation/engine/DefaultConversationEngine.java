package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationEventDto;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.NextStepDto;
import com.consilens.ai.conversation.api.model.PendingApprovalDto;
import com.consilens.ai.conversation.api.model.PendingQuestionDto;
import com.consilens.ai.conversation.api.model.SessionSnapshot;
import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.TurnDecision;
import com.consilens.ai.conversation.error.ConversationErrorCode;
import com.consilens.ai.runtime.intent.DefaultIntentRouter;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.PendingApprovalState;
import com.consilens.ai.session.model.PendingQuestionState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Default stateful conversation engine backed by the existing task runtime.
 */
public class DefaultConversationEngine implements ConversationEngine {

    private static final Set<String> SUPPORTED_CONNECTORS = Set.of(
            "mysql", "postgresql", "oracle", "sqlserver", "presto",
            "clickhouse", "trino", "tidb", "starrocks", "doris");
    private static final Pattern SOURCE_TYPE_PATTERN = Pattern.compile("(?i)\\bsourceType\\s*[:=]\\s*([a-zA-Z0-9_-]+)");
    private static final Pattern TARGET_TYPE_PATTERN = Pattern.compile("(?i)\\btargetType\\s*[:=]\\s*([a-zA-Z0-9_-]+)");
    private static final Pattern SOURCE_TABLE_PATTERN = Pattern.compile("(?i)\\bsourceTable\\s*[:=]\\s*([^\\n,]+)");
    private static final Pattern TARGET_TABLE_PATTERN = Pattern.compile("(?i)\\btargetTable\\s*[:=]\\s*([^\\n,]+)");
    private static final Pattern SOURCE_QUERY_PATTERN = Pattern.compile("(?i)\\bsourceQuery\\s*[:=]\\s*(.+)");
    private static final Pattern TARGET_QUERY_PATTERN = Pattern.compile("(?i)\\btargetQuery\\s*[:=]\\s*(.+)");
    private static final Pattern KEYS_PATTERN = Pattern.compile("(?i)\\bkeys\\s*[:=]\\s*([a-zA-Z0-9_,]+)");
    private static final Pattern SOURCE_KEYS_PATTERN = Pattern.compile("(?i)\\bsourceKeys\\s*[:=]\\s*([a-zA-Z0-9_,]+)");
    private static final Pattern TARGET_KEYS_PATTERN = Pattern.compile("(?i)\\btargetKeys\\s*[:=]\\s*([a-zA-Z0-9_,]+)");
    private static final Pattern YAML_SOURCE_TYPE_PATTERN = Pattern.compile("(?is)source\\s*:\\s*.*?\\btype\\s*:\\s*([a-zA-Z0-9_-]+)");
    private static final Pattern YAML_TARGET_TYPE_PATTERN = Pattern.compile("(?is)target\\s*:\\s*.*?\\btype\\s*:\\s*([a-zA-Z0-9_-]+)");
    private static final Pattern YAML_SOURCE_RESOURCE_PATTERN = Pattern.compile("(?is)source\\s*:\\s*.*?resource\\s*:\\s*.*?\\b(name|path|query)\\s*:");
    private static final Pattern YAML_TARGET_RESOURCE_PATTERN = Pattern.compile("(?is)target\\s*:\\s*.*?resource\\s*:\\s*.*?\\b(name|path|query)\\s*:");
    private static final Pattern YAML_KEYS_PATTERN = Pattern.compile("(?is)comparison\\s*:\\s*.*?keys\\s*:\\s*.*?-\\s*[^\\n]+");
    private static final String VALIDATION_MARKER = "\n\n[输入校验]\n";

    private final AiSessionStore sessionStore;
    private final TurnPlanner turnPlanner;
    private final PlannerAgent plannerAgent;
    private final PlannerBridge plannerBridge;
    private final PlanContextAssembler planContextAssembler;
    private final ClarificationManager clarificationManager;
    private final ApprovalManager approvalManager;
    private final ActionExecutor actionExecutor;

    public DefaultConversationEngine(AiSessionStore sessionStore,
                                     TurnPlanner turnPlanner,
                                     ClarificationManager clarificationManager,
                                     ApprovalManager approvalManager,
                                     ActionExecutor actionExecutor) {
        this.sessionStore = sessionStore;
        this.turnPlanner = turnPlanner;
        this.plannerAgent = null;
        this.plannerBridge = null;
        this.planContextAssembler = null;
        this.clarificationManager = clarificationManager;
        this.approvalManager = approvalManager;
        this.actionExecutor = actionExecutor;
    }

    public DefaultConversationEngine(AiSessionStore sessionStore,
                                     PlannerAgent plannerAgent,
                                     PlannerBridge plannerBridge,
                                     PlanContextAssembler planContextAssembler,
                                     ClarificationManager clarificationManager,
                                     ApprovalManager approvalManager,
                                     ActionExecutor actionExecutor) {
        this.sessionStore = sessionStore;
        this.turnPlanner = new DefaultTurnPlanner(new DefaultIntentRouter());
        this.plannerAgent = plannerAgent;
        this.plannerBridge = plannerBridge;
        this.planContextAssembler = planContextAssembler;
        this.clarificationManager = clarificationManager;
        this.approvalManager = approvalManager;
        this.actionExecutor = actionExecutor;
    }
    public ConversationResponse handleUserTurn(String sessionId, String input) {
        return handleUserTurn(sessionId, input, Map.of());
    }

    @Override
    public ConversationResponse handleUserTurn(String sessionId, String input, Map<String, Object> attributes) {
        AiSession session = loadOrCreate(sessionId);
        if (session.getPendingApproval() != null) {
            if (approvalManager.isApprove(input)) {
                return approve(session.getSessionId());
            }
            if (approvalManager.isDeny(input)) {
                return deny(session.getSessionId());
            }
            return approvalResponse(session, session.getPendingApproval().getPrompt());
        }

        String effectiveInput = input == null ? "" : input.trim();
        if (session.getPendingQuestion() != null) {
            if (isStructuredClarificationInput(effectiveInput)) {
                List<String> violations = validateClarificationAnswer(session.getPendingQuestion(), effectiveInput);
                if (!violations.isEmpty()) {
                    String baseQuestion = stripValidationBlock(session.getPendingQuestion().getQuestion());
                    PendingQuestionState refreshed = session.getPendingQuestion().toBuilder()
                            .question(baseQuestion + VALIDATION_MARKER + String.join("\n", violations))
                            .build();
                    session = updateSession(session, session.toBuilder()
                            .pendingQuestion(refreshed)
                            .status("awaiting_clarification")
                            .updatedAt(Instant.now())
                            .build());
                    return questionResponse(session, refreshed);
                }
            }
            effectiveInput = clarificationManager.merge(session.getPendingQuestion(), effectiveInput);
            session = updateSession(session, session.toBuilder()
                    .pendingQuestion(null)
                    .updatedAt(Instant.now())
                    .build());
        }
        TurnDecision decision = decide(PlannerContext.builder()
                .session(session)
                .rawInput(effectiveInput)
                .attributes(attributes == null ? Map.of() : attributes)
                .build());
        return handleDecision(session, decision);
    }

    @Override
    public ConversationResponse executeCommand(ConversationCommandRequest request) {
        if (request == null || request.getCommandName() == null || request.getCommandName().isBlank()) {
            return error(loadOrCreate(request == null ? null : request.getSessionId()),
                    ConversationErrorCode.COMMAND_NAME_REQUIRED,
                    "Command name is required.");
        }
        AiSession session = loadOrCreate(request.getSessionId());
        TurnDecision decision = decide(PlannerContext.builder()
                .session(session)
                .commandName(request.getCommandName())
                .commandArgument(request.getArgument())
                .attributes(request.getAttributes() == null ? Map.of() : request.getAttributes())
                .build());
        return handleDecision(session, decision);
    }

    private TurnDecision decide(PlannerContext context) {
        if (context != null && context.getCommandName() != null && !context.getCommandName().isBlank()) {
            return turnPlanner.plan(context);
        }
        if (plannerAgent != null) {
            return plannerBridge.toTurnDecision(plannerAgent.plan(context), context, planContextAssembler);
        }
        // plannerAgent not configured — use TurnPlanner directly (test/legacy path)
        if (turnPlanner != null) {
            return turnPlanner.plan(context);
        }
        return TurnDecision.builder()
                .type(TurnDecision.Type.MESSAGE)
                .message("[AI ERROR] No LLM planner configured. Please check your backend settings with `consilens ai doctor`.")
                .build();
    }

    @Override
    public ConversationResponse approve(String sessionId) {
        AiSession session = loadOrCreate(sessionId);
        PendingApprovalState pendingApproval = session.getPendingApproval();
        if (pendingApproval == null) {
            return error(session,
                    ConversationErrorCode.CONVERSATION_NO_PENDING_APPROVAL,
                    "Current session has no pending approval.");
        }
        ActionPlan restored = approvalManager.restore(session.getSessionId(), pendingApproval).toBuilder()
                .requiresApproval(true)
                .build();
        session = updateSession(session, session.toBuilder()
                .pendingApproval(null)
                .updatedAt(Instant.now())
                .build());
        return fromTaskResult(reload(session.getSessionId()), actionExecutor.execute(restored));
    }

    @Override
    public ConversationResponse deny(String sessionId) {
        AiSession session = loadOrCreate(sessionId);
        if (session.getPendingApproval() == null) {
            return error(session,
                    ConversationErrorCode.CONVERSATION_NO_PENDING_APPROVAL,
                    "Current session has no pending approval.");
        }
        session = updateSession(session, session.toBuilder()
                .pendingApproval(null)
                .status("approval_denied")
                .updatedAt(Instant.now())
                .build());
        return ConversationResponse.builder()
                .type(ConversationResponse.Type.MESSAGE)
                .message("Pending approval cleared.")
                .suggestedNextStep(nextStep("plan", "Provide a new goal or inspect the current config."))
                .session(snapshot(session))
                .build();
    }

    private ConversationResponse handleDecision(AiSession session, TurnDecision decision) {
        if (decision == null || decision.getType() == null) {
            return error(session, ConversationErrorCode.INTERNAL_ERROR, "Planner returned no decision.");
        }
        if (decision.getType() == TurnDecision.Type.MESSAGE) {
            return ConversationResponse.builder()
                    .type(ConversationResponse.Type.MESSAGE)
                    .message(decision.getMessage())
                    .suggestedNextStep(nextStep("plan", "Describe a comparison goal."))
                    .session(snapshot(session))
                    .build();
        }
        if (decision.getType() == TurnDecision.Type.QUESTION) {
            PendingQuestionState pendingQuestion = clarificationManager.create(decision.getQuestion());
            session = updateSession(session, session.toBuilder()
                    .pendingQuestion(pendingQuestion)
                    .status("awaiting_clarification")
                    .updatedAt(Instant.now())
                    .build());
            return questionResponse(session, pendingQuestion);
        }
        if (decision.getActionPlan() == null) {
            return error(session, ConversationErrorCode.INTERNAL_ERROR, "Planner returned an empty action plan.");
        }
        AiTaskResult taskResult;
        try {
            taskResult = actionExecutor.execute(decision.getActionPlan());
        } catch (RuntimeException e) {
            return error(session, ConversationErrorCode.INTERNAL_ERROR, "Action execution failed: " + e.getMessage());
        }
        AiSession reloaded = reload(session.getSessionId());
        if (taskResult.getStatus() == AiTurnResult.Status.REQUIRES_APPROVAL) {
            PendingApprovalState pendingApproval = approvalManager.create(
                    decision.getActionPlan(),
                    taskResult.getSummary());
            reloaded = updateSession(reloaded, reloaded.toBuilder()
                    .pendingApproval(pendingApproval)
                    .status("awaiting_approval")
                    .latestApprovalId("pending")
                    .updatedAt(Instant.now())
                    .build());
            return approvalResponse(reloaded, taskResult.getSummary());
        }
        reloaded = updateSession(reloaded, updateSummaryAndObjective(reloaded, decision.getActionPlan(), taskResult));
        return fromTaskResult(reloaded, taskResult);
    }

    private ConversationResponse fromTaskResult(AiSession session, AiTaskResult result) {
        if (result == null) {
            return error(session, ConversationErrorCode.INTERNAL_ERROR, "Task returned no result.");
        }
        ConversationResponse.Type type = result.isSuccess()
                ? ConversationResponse.Type.MESSAGE
                : ConversationResponse.Type.ERROR;
        ConversationErrorCode errorCode = type == ConversationResponse.Type.ERROR
                ? deriveErrorCode(result.getSummary())
                : null;
        return ConversationResponse.builder()
                .type(type)
                .message(result.getSummary())
                .errorCode(errorCode == null ? null : errorCode.code())
                .suggestedNextStep(nextStep(result.getSuggestedNextAction(),
                        result.isSuccess() ? "Continue the closed loop." : "Inspect the error and adjust the config."))
                .session(snapshot(session))
                .events(mapEvents(result))
                .build();
    }

    private List<ConversationEventDto> mapEvents(AiTaskResult result) {
        if (result == null || result.getEvents() == null || result.getEvents().isEmpty()) {
            return List.of();
        }
        return result.getEvents().stream()
                .map(event -> ConversationEventDto.builder()
                        .stage(event.getStage())
                        .status(event.getStatus())
                        .message(event.getMessage())
                        .artifactId(event.getArtifactId())
                        .artifactType(event.getArtifactType())
                        .metadata(event.getMetadata())
                        .build())
                .collect(Collectors.toList());
    }

    private ConversationResponse questionResponse(AiSession session, PendingQuestionState pendingQuestion) {
        return ConversationResponse.builder()
                .type(ConversationResponse.Type.QUESTION)
                .message(pendingQuestion.getQuestion())
                .suggestedNextStep(nextStep("answer_question", "Answer the clarification to continue."))
                .session(snapshot(session))
                .build();
    }

    private String stripValidationBlock(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        int idx = question.indexOf(VALIDATION_MARKER);
        return idx < 0 ? question : question.substring(0, idx);
    }

    private List<String> validateClarificationAnswer(PendingQuestionState pendingQuestion, String answer) {
        List<String> violations = new ArrayList<>();
        String text = answer == null ? "" : answer.trim();
        if (text.isEmpty()) {
            violations.add("- 输入不能为空，请按模板填写本轮字段。");
            return violations;
        }
        if (pendingQuestion == null || pendingQuestion.getExpectedKeys() == null || pendingQuestion.getExpectedKeys().isEmpty()) {
            return violations;
        }
        for (String rawKey : pendingQuestion.getExpectedKeys()) {
            String key = canonicalKey(rawKey);
            if (key == null) {
                continue;
            }
            switch (key) {
                case "sourceType":
                    validateConnector(text, true, violations);
                    break;
                case "targetType":
                    validateConnector(text, false, violations);
                    break;
                case "sourceResource":
                    if (!hasSourceResource(text)) {
                        violations.add("- 请填写 `sourceTable=...` 或 `sourceQuery=...`（二选一）。");
                    }
                    break;
                case "targetResource":
                    if (!hasTargetResource(text)) {
                        violations.add("- 请填写 `targetTable=...` 或 `targetQuery=...`（二选一）。");
                    }
                    break;
                case "keys":
                    if (!hasKeys(text)) {
                        violations.add("- 请填写 `keys=...`（多列用逗号，如 `keys=order_id,user_id`）。");
                    }
                    break;
                case "sourceKeys":
                    if (!hasPattern(SOURCE_KEYS_PATTERN, text)) {
                        violations.add("- 请填写 `sourceKeys=...`。");
                    }
                    break;
                case "targetKeys":
                    if (!hasPattern(TARGET_KEYS_PATTERN, text)) {
                        violations.add("- 请填写 `targetKeys=...`。");
                    }
                    break;
                default:
                    if (!text.toLowerCase().contains(key.toLowerCase())) {
                        violations.add("- 缺少字段 `" + rawKey + "`，请按 `key=value` 形式补充。");
                    }
                    break;
            }
        }
        return deduplicate(violations);
    }

    private boolean isStructuredClarificationInput(String input) {
        if (input == null) {
            return false;
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return false;
        }
        return text.contains("=")
                || text.contains("source:")
                || text.contains("target:")
                || text.contains("comparison:")
                || text.contains("\n- ");
    }

    private void validateConnector(String text, boolean source, List<String> violations) {
        Pattern pattern = source ? SOURCE_TYPE_PATTERN : TARGET_TYPE_PATTERN;
        Pattern yamlPattern = source ? YAML_SOURCE_TYPE_PATTERN : YAML_TARGET_TYPE_PATTERN;
        String label = source ? "sourceType" : "targetType";
        String value = firstMatch(pattern, text);
        if (value == null) {
            value = firstMatch(yamlPattern, text);
        }
        if (value == null || value.isBlank()) {
            violations.add("- 请填写 `" + label + "=...`，例如 `" + label + "=mysql`。");
            return;
        }
        String normalized = value.trim().toLowerCase();
        if (!SUPPORTED_CONNECTORS.contains(normalized)) {
            violations.add("- `" + label + "` 不在支持列表："
                    + String.join(", ", SUPPORTED_CONNECTORS) + "。");
        }
    }

    private boolean hasSourceResource(String text) {
        return hasPattern(SOURCE_TABLE_PATTERN, text)
                || hasPattern(SOURCE_QUERY_PATTERN, text)
                || hasPattern(YAML_SOURCE_RESOURCE_PATTERN, text);
    }

    private boolean hasTargetResource(String text) {
        return hasPattern(TARGET_TABLE_PATTERN, text)
                || hasPattern(TARGET_QUERY_PATTERN, text)
                || hasPattern(YAML_TARGET_RESOURCE_PATTERN, text);
    }

    private boolean hasKeys(String text) {
        return hasPattern(KEYS_PATTERN, text)
                || hasPattern(SOURCE_KEYS_PATTERN, text)
                || hasPattern(TARGET_KEYS_PATTERN, text)
                || hasPattern(YAML_KEYS_PATTERN, text);
    }

    private boolean hasPattern(Pattern pattern, String text) {
        if (pattern == null || text == null) {
            return false;
        }
        return pattern.matcher(text).find();
    }

    private String firstMatch(Pattern pattern, String text) {
        if (pattern == null || text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.groupCount() >= 1 ? matcher.group(1) : null;
    }

    private List<String> deduplicate(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            return List.of();
        }
        List<String> dedup = new ArrayList<>();
        for (String violation : violations) {
            if (violation != null && !dedup.contains(violation)) {
                dedup.add(violation);
            }
        }
        return dedup;
    }

    private String canonicalKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.toLowerCase().replace("_", "").replace("-", "").trim();
        switch (key) {
            case "sourcetype":
                return "sourceType";
            case "targettype":
                return "targetType";
            case "sourcetable":
            case "sourcequery":
            case "sourceresource":
                return "sourceResource";
            case "targettable":
            case "targetquery":
            case "targetresource":
                return "targetResource";
            case "keys":
            case "key":
                return "keys";
            case "sourcekeys":
                return "sourceKeys";
            case "targetkeys":
                return "targetKeys";
            default:
                return rawKey;
        }
    }

    private ConversationResponse approvalResponse(AiSession session, String prompt) {
        return ConversationResponse.builder()
                .type(ConversationResponse.Type.APPROVAL)
                .message(prompt)
                .errorCode(ConversationErrorCode.APPROVAL_REQUIRED.code())
                .suggestedNextStep(nextStep("approve_execute", "Use /approve execute to continue or /deny to cancel."))
                .session(snapshot(session))
                .build();
    }

    private ConversationResponse error(AiSession session, ConversationErrorCode errorCode, String message) {
        return ConversationResponse.builder()
                .type(ConversationResponse.Type.ERROR)
                .message(message)
                .errorCode(errorCode.code())
                .session(snapshot(session))
                .build();
    }

    private NextStepDto nextStep(String code, String description) {
        return NextStepDto.builder()
                .code(code)
                .description(description)
                .build();
    }

    private AiSession loadOrCreate(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return sessionStore.create();
        }
        return sessionStore.load(sessionId).orElseGet(() -> sessionStore.create(sessionId));
    }

    private AiSession reload(String sessionId) {
        return sessionStore.load(sessionId).orElseGet(() -> sessionStore.create(sessionId));
    }

    private AiSession updateSession(AiSession previous, AiSession updated) {
        sessionStore.save(updated);
        return updated;
    }

    private AiSession updateSummaryAndObjective(AiSession session, ActionPlan actionPlan, AiTaskResult taskResult) {
        String objective = actionPlan.getUserInput();
        if ((objective == null || objective.isBlank()) && actionPlan.getCommandArgument() != null) {
            objective = actionPlan.getCommandArgument();
        }
        String summary = taskResult == null ? null : compact(taskResult.getSummary(), 180);
        return session.toBuilder()
                .currentObjective(objective == null || objective.isBlank() ? session.getCurrentObjective() : objective)
                .summary(summary == null || summary.isBlank() ? session.getSummary() : summary)
                .updatedAt(Instant.now())
                .build();
    }

    private String compact(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String compacted = value.replaceAll("\\s+", " ").trim();
        if (compacted.length() <= maxLength) {
            return compacted;
        }
        return compacted.substring(0, maxLength) + "...";
    }

    private ConversationErrorCode deriveErrorCode(String message) {
        if (message == null || message.isBlank()) {
            return ConversationErrorCode.INTERNAL_ERROR;
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("no task registered")) {
            return ConversationErrorCode.COMMAND_UNSUPPORTED;
        }
        if (normalized.contains("no current config") || normalized.contains("config artifact")) {
            return ConversationErrorCode.CONFIG_NOT_FOUND;
        }
        if (normalized.contains("approval")) {
            return ConversationErrorCode.APPROVAL_REQUIRED;
        }
        return ConversationErrorCode.INTERNAL_ERROR;
    }

    private SessionSnapshot snapshot(AiSession session) {
        if (session == null) {
            return null;
        }
        return SessionSnapshot.builder()
                .sessionId(session.getSessionId())
                .summary(session.getSummary())
                .currentObjective(session.getCurrentObjective())
                .status(session.getStatus())
                .currentTask(session.getCurrentTask())
                .currentConfigArtifactId(session.getCurrentConfigArtifactId())
                .latestRunArtifactId(session.getLatestRunArtifactId())
                .latestDiagnosisArtifactId(session.getLatestDiagnosisArtifactId())
                .latestAuditArtifactId(session.getLatestAuditArtifactId())
                .pendingQuestion(question(session.getPendingQuestion()))
                .pendingApproval(approval(session.getPendingApproval()))
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private PendingQuestionDto question(PendingQuestionState pendingQuestion) {
        if (pendingQuestion == null) {
            return null;
        }
        PendingQuestionDto.PendingQuestionDtoBuilder builder = PendingQuestionDto.builder()
                .question(pendingQuestion.getQuestion())
                .blocking(pendingQuestion.isBlocking())
                .originalRequest(pendingQuestion.getOriginalRequest())
                .createdAt(pendingQuestion.getCreatedAt());
        if (pendingQuestion.getExpectedKeys() != null) {
            pendingQuestion.getExpectedKeys().forEach(builder::expectedKey);
        }
        return builder.build();
    }

    private PendingApprovalDto approval(PendingApprovalState pendingApproval) {
        if (pendingApproval == null) {
            return null;
        }
        return PendingApprovalDto.builder()
                .type(pendingApproval.getType())
                .prompt(pendingApproval.getPrompt())
                .relatedCommandName(pendingApproval.getCommandName())
                .relatedCommandArgument(pendingApproval.getCommandArgument())
                .createdAt(pendingApproval.getCreatedAt())
                .build();
    }
}
