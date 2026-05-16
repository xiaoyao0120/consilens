package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.conversation.engine.model.ActionType;
import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.QuestionSpec;
import com.consilens.ai.conversation.engine.model.TurnDecision;
import com.consilens.ai.runtime.intent.AiIntent;
import com.consilens.ai.runtime.intent.CompareIntentHintExtractor;
import com.consilens.ai.runtime.intent.IntentRouter;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.execution.model.ConfigGenerationRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Default planner that upgrades the old intent router into a stateful conversation planner.
 */
public class DefaultTurnPlanner implements TurnPlanner {

    private static final String CONFIG_REQUEST_KEY = "configRequest";
    private static final List<String> CONNECTOR_TYPES = Arrays.asList(
            "mysql", "postgresql", "postgres", "oracle", "clickhouse", "sqlserver", "starrocks", "doris");
    private static final Pattern KEY_PATTERN = Pattern.compile("(?i)(\\bkeys?\\b|\\bprimary\\s+key\\b|\\bpk\\b|主键|keys\\s*:)");
    private static final Pattern SQL_PATTERN = Pattern.compile("(?i)(\\bselect\\b|\\bwith\\b|sql\\s*:)");
    private static final Pattern JOIN_PATTERN = Pattern.compile("(?i)(\\bjoin\\b|关联)");
    private static final Pattern FIELD_PATTERN = Pattern.compile("(?i)(\\bfields?\\b|\\bcolumns?\\b|字段|columns\\s*:|fields\\s*:)");
    private static final Pattern QUALIFIED_RESOURCE_PATTERN = Pattern.compile("(?i)\\b([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)\\b");
    private static final Pattern CN_TABLE_PATTERN = Pattern.compile("(?i)([a-z_][a-z0-9_]*)\\s*表");
    private static final Pattern EN_TABLE_PATTERN = Pattern.compile("(?i)\\b(?:table\\s+([a-z_][a-z0-9_]*)|([a-z_][a-z0-9_]*)\\s+table)\\b");

    private final IntentRouter intentRouter;

    public DefaultTurnPlanner(IntentRouter intentRouter) {
        this.intentRouter = intentRouter;
    }

    @Override
    public TurnDecision plan(PlannerContext context) {
        if (context.getCommandName() != null && !context.getCommandName().isBlank()) {
            return planCommand(context);
        }
        String rawInput = context.getRawInput() == null ? "" : context.getRawInput().trim();
        if (rawInput.isEmpty()) {
            return TurnDecision.builder()
                    .type(TurnDecision.Type.MESSAGE)
                    .message("Describe a comparison goal, or use /plan, /validate, /dry-run, /run, /diff, /remember, /forget, /config, /sessions, /resume, /new.")
                    .build();
        }
        AiIntent intent = intentRouter.route(context.getSession(), rawInput);
        QuestionSpec clarification = clarificationQuestion(context.getSession(), rawInput);
        if ((intent == AiIntent.PLAN_CONFIG || intent == AiIntent.RUN_DIFF) && clarification != null) {
            return TurnDecision.builder()
                    .type(TurnDecision.Type.QUESTION)
                    .question(clarification)
                    .build();
        }
        ActionPlan.ActionPlanBuilder planBuilder = ActionPlan.builder()
                .sessionId(context.getSession().getSessionId())
                .actionType(map(intent))
                .commandName(defaultCommandName(intent))
                .commandArgument(rawInput)
                .userInput(rawInput);
        // For PLAN_CONFIG from natural language, attach a ConfigGenerationRequest so PlanConfigTask
        // has the goal available without requiring an explicit /plan command invocation.
        if (intent == AiIntent.PLAN_CONFIG || intent == AiIntent.MODIFY_CONFIG) {
            planBuilder.attribute(CONFIG_REQUEST_KEY,
                    CompareIntentHintExtractor.enrich(context.getSession().getSessionId(), rawInput, null));
        }
        return TurnDecision.builder()
                .type(TurnDecision.Type.ACTION)
                .actionPlan(planBuilder.build())
                .build();
    }

    private TurnDecision planCommand(PlannerContext context) {
        String commandName = context.getCommandName().trim().toLowerCase(Locale.ROOT);
        if (("plan".equals(commandName)
                || "run".equals(commandName)
                || "check".equals(commandName)
                || "validate".equals(commandName)
                || "dry-run".equals(commandName)
                || "diff".equals(commandName))
                && (context.getCommandArgument() == null || context.getCommandArgument().isBlank())
                && !hasCurrentConfig(context.getSession())
                && !hasConfigRequest(context)) {
            return TurnDecision.builder()
                    .type(TurnDecision.Type.QUESTION)
                    .question(QuestionSpec.builder()
                            .question("当前还没有配置，请先描述 source、target，以及主键字段。")
                            .originalRequest(commandName)
                            .expectedKey("source")
                            .expectedKey("target")
                            .expectedKey("keys")
                            .blocking(true)
                            .build())
                    .build();
        }
        String goal = effectiveGoal(context);
        if (requiresGoalClarification(commandName, context.getSession(), goal)) {
            return TurnDecision.builder()
                    .type(TurnDecision.Type.QUESTION)
                    .question(commandClarificationQuestion(goal))
                    .build();
        }
        ActionPlan.ActionPlanBuilder builder = ActionPlan.builder()
                .sessionId(context.getSession().getSessionId())
                .commandName(commandName)
                .commandArgument(context.getCommandArgument())
                .actionType(mapCommand(commandName))
                .userInput(context.getCommandArgument());
        Map<String, Object> attributes = context.getAttributes();
        if (attributes != null) {
            attributes.forEach(builder::attribute);
        }
        return TurnDecision.builder()
                .type(TurnDecision.Type.ACTION)
                .actionPlan(builder.build())
                .build();
    }

    private boolean hasConfigRequest(PlannerContext context) {
        return context.getAttributes() != null && context.getAttributes().containsKey(CONFIG_REQUEST_KEY);
    }

    private QuestionSpec clarificationQuestion(AiSession session, String rawInput) {
        if (hasCurrentConfig(session)) {
            return null;
        }
        String normalized = rawInput.toLowerCase(Locale.ROOT);
        boolean isCompareIntent = normalized.contains("compare") || normalized.contains("diff")
                || normalized.contains("对比") || normalized.contains("比较")
                || normalized.contains("差异") || normalized.contains("一致性");
        if (!isCompareIntent) {
            return null;
        }
        if (!hasSourceAndTarget(rawInput)) {
            return QuestionSpec.builder()
                    .question(sourceTargetQuestion(rawInput))
                    .originalRequest(rawInput)
                    .expectedKey("source")
                    .expectedKey("target")
                    .blocking(true)
                    .build();
        }
        if (!hasKeys(rawInput)) {
            return QuestionSpec.builder()
                    .question(keysQuestion(rawInput))
                    .originalRequest(rawInput)
                    .expectedKey("keys")
                    .blocking(true)
                    .build();
        }
        return null;
    }

    private boolean hasCurrentConfig(AiSession session) {
        return session != null
                && session.getCurrentConfigArtifactId() != null
                && !session.getCurrentConfigArtifactId().isBlank();
    }

    private ActionType map(AiIntent intent) {
        switch (intent) {
            case PLAN_CONFIG:
            case MODIFY_CONFIG:
                return ActionType.PLAN_CONFIG;
            case EXPLAIN_CONFIG:
                return ActionType.EXPLAIN_CONFIG;
            case RUN_DIFF:
                return ActionType.RUN_DIFF;
            case DIAGNOSE_RESULT:
                return ActionType.DIAGNOSE_RESULT;
            case REPAIR_CONFIG:
                return ActionType.REPAIR_CONFIG;
            case NEED_CLARIFICATION:
            case GENERAL_QA:
            default:
                return ActionType.GENERAL_RESPONSE;
        }
    }

    private boolean requiresGoalClarification(String commandName, AiSession session, String goal) {
        if (goal == null || goal.isBlank()) {
            return false;
        }
        if (!"plan".equals(commandName) && !"run".equals(commandName)) {
            return false;
        }
        return !hasCurrentConfig(session) && (!hasSourceAndTarget(goal) || !hasKeys(goal));
    }

    private QuestionSpec commandClarificationQuestion(String goal) {
        if (!hasSourceAndTarget(goal)) {
            return QuestionSpec.builder()
                    .question(sourceTargetQuestion(goal))
                    .originalRequest(goal)
                    .expectedKey("source")
                    .expectedKey("target")
                    .blocking(true)
                    .build();
        }
        return QuestionSpec.builder()
                .question(keysQuestion(goal))
                .originalRequest(goal)
                .expectedKey("keys")
                .blocking(true)
                .build();
    }

    private String effectiveGoal(PlannerContext context) {
        if (context.getCommandArgument() != null && !context.getCommandArgument().isBlank()) {
            return context.getCommandArgument();
        }
        if (context.getAttributes() == null) {
            return null;
        }
        ConfigGenerationRequest request = (ConfigGenerationRequest) context.getAttributes().get(CONFIG_REQUEST_KEY);
        return request == null ? null : request.getGoal();
    }

    private boolean hasSourceAndTarget(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return false;
        }
        String normalized = rawInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("source:") && normalized.contains("target:")) {
            return true;
        }
        int matches = 0;
        for (String connector : CONNECTOR_TYPES) {
            if (normalized.contains(connector)) {
                matches++;
            }
        }
        return matches >= 2 || ((normalized.contains("->") || normalized.contains(" to ")) && matches >= 1);
    }

    private boolean hasKeys(String rawInput) {
        return rawInput != null && KEY_PATTERN.matcher(rawInput).find();
    }

    private String sourceTargetQuestion(String rawInput) {
        String recognized = recognizedCompareScope(rawInput);
        if (hasSqlResource(rawInput)) {
            return prefix(recognized)
                    + "检测到 SQL 资源线索。继续生成配置前，请明确 source 与 target，例如 `mysql: SELECT ... -> postgresql: SELECT ...`。";
        }
        return prefix(recognized)
                + "继续生成配置前，请先说明 source 与 target，例如 `mysql.orders -> postgresql.orders`。";
    }

    private String keysQuestion(String rawInput) {
        String recognized = recognizedCompareScope(rawInput);
        if (hasJoinClue(rawInput)) {
            return prefix(recognized)
                    + "检测到 join 场景。继续生成配置还需要 compare keys，例如 `order_id,user_id`。";
        }
        if (hasFieldClue(rawInput)) {
            return prefix(recognized)
                    + "检测到 fields/columns 线索。继续生成配置还需要主键字段，例如 `id` 或 `order_id`。";
        }
        return prefix(recognized)
                + "继续生成配置还需要 compare keys。若两边都用同一个主键，直接回复 `id` 即可；若不同，请回复 `sourceKeys=id targetKeys=user_id`。";
    }

    private String prefix(String recognized) {
        return recognized == null || recognized.isBlank() ? "" : recognized + " ";
    }

    private String recognizedCompareScope(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }
        List<String> resources = extractQualifiedResources(rawInput);
        if (resources.size() >= 2) {
            return "已识别到你要比较 " + resources.get(0) + " 和 " + resources.get(1) + " 的数据。";
        }

        List<String> connectors = extractConnectors(rawInput);
        String table = extractSharedTable(rawInput);
        if (connectors.size() >= 2 && table != null) {
            return "已识别到你要比较 " + connectors.get(0) + "." + table + " 和 " + connectors.get(1) + "." + table + " 的数据。";
        }
        if (connectors.size() >= 2) {
            return "已识别到你要比较 " + connectors.get(0) + " 和 " + connectors.get(1) + " 之间的数据。";
        }
        if (table != null) {
            return "已识别到你要比较 " + table + " 的数据。";
        }
        return "";
    }

    private List<String> extractQualifiedResources(String rawInput) {
        List<String> resources = new ArrayList<>();
        Matcher matcher = QUALIFIED_RESOURCE_PATTERN.matcher(rawInput);
        while (matcher.find()) {
            String connector = matcher.group(1).toLowerCase(Locale.ROOT);
            String resource = matcher.group(2);
            if (CONNECTOR_TYPES.contains(connector)) {
                resources.add(connector + "." + resource);
            }
        }
        return resources;
    }

    private List<String> extractConnectors(String rawInput) {
        String normalized = rawInput.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> connectors = new LinkedHashSet<>();
        for (String connector : CONNECTOR_TYPES) {
            if (normalized.contains(connector)) {
                connectors.add(connector);
            }
        }
        return new ArrayList<>(connectors);
    }

    private String extractSharedTable(String rawInput) {
        Matcher cnMatcher = CN_TABLE_PATTERN.matcher(rawInput);
        if (cnMatcher.find()) {
            return cnMatcher.group(1);
        }
        Matcher enMatcher = EN_TABLE_PATTERN.matcher(rawInput);
        if (enMatcher.find()) {
            return enMatcher.group(1) != null ? enMatcher.group(1) : enMatcher.group(2);
        }
        return null;
    }

    private boolean hasSqlResource(String rawInput) {
        return rawInput != null && SQL_PATTERN.matcher(rawInput).find();
    }

    private boolean hasJoinClue(String rawInput) {
        return rawInput != null && JOIN_PATTERN.matcher(rawInput).find();
    }

    private boolean hasFieldClue(String rawInput) {
        return rawInput != null && FIELD_PATTERN.matcher(rawInput).find();
    }

    private String defaultCommandName(AiIntent intent) {
        switch (intent) {
            case PLAN_CONFIG:
            case MODIFY_CONFIG:
                return "plan";
            case EXPLAIN_CONFIG:
                return "explain";
            case RUN_DIFF:
                return "run";
            case DIAGNOSE_RESULT:
                return "diagnose";
            case REPAIR_CONFIG:
                return "repair";
            case NEED_CLARIFICATION:
            case GENERAL_QA:
            default:
                return "doctor";
        }
    }

    private ActionType mapCommand(String commandName) {
        switch (commandName) {
            case "plan":
                return ActionType.PLAN_CONFIG;
            case "run":
            case "diff":
                return ActionType.RUN_DIFF;
            case "diagnose":
            case "analyze-last":
                return ActionType.DIAGNOSE_RESULT;
            case "repair":
                return ActionType.REPAIR_CONFIG;
            case "explain":
            case "check":
            case "validate":
            case "dry-run":
                return ActionType.EXPLAIN_CONFIG;
            case "doctor":
            default:
                return ActionType.GENERAL_RESPONSE;
        }
    }
}
