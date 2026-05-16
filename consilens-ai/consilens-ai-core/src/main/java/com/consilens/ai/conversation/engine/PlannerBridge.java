package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.PlannerResult;
import com.consilens.ai.conversation.engine.model.PlannerRoute;
import com.consilens.ai.conversation.engine.model.PlannerType;
import com.consilens.ai.conversation.engine.model.QuestionSpec;
import com.consilens.ai.conversation.engine.model.TurnDecision;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ArrayList;

/**
 * Converts structured planner output to runtime turn decisions.
 */
public class PlannerBridge {

    private final ExampleTemplateStore exampleTemplateStore;

    public PlannerBridge() {
        this(new ExampleTemplateStore());
    }

    public PlannerBridge(ExampleTemplateStore exampleTemplateStore) {
        this.exampleTemplateStore = exampleTemplateStore;
    }

    public TurnDecision toTurnDecision(PlannerResult result,
                                       PlannerContext context,
                                       PlanContextAssembler assembler) {
        if (result == null || result.getType() == null) {
            return TurnDecision.builder()
                    .type(TurnDecision.Type.MESSAGE)
                    .message("Planner returned no decision.")
                    .build();
        }
        if (result.getType() == PlannerType.CHAT) {
            String rawInput = context == null ? "" : firstNonBlank(context.getRawInput(), "");
            // When user asks for a template, always return the actual example YAML (not LLM-generated YAML)
            // Also handle when LLM answered with just an example name (looks like an example key)
            boolean looksLikeExampleName = result.getAnswer() != null
                    && result.getAnswer().matches("[a-z][a-z0-9-]+")
                    && exampleTemplateStore != null
                    && !exampleTemplateStore.isEmpty()
                    && exampleTemplateStore.getAll().stream()
                        .anyMatch(e -> e.getName().equals(result.getAnswer()));
            if (isTemplateRequest(rawInput) || looksLikeExampleName) {
                return TurnDecision.builder()
                        .type(TurnDecision.Type.MESSAGE)
                        .message(buildTemplateResponse(rawInput, result.getAnswer()))
                        .build();
            }
            return TurnDecision.builder()
                    .type(TurnDecision.Type.MESSAGE)
                    .message(firstNonBlank(result.getAnswer(), result.getNormalizedGoal(), "No planner answer available."))
                    .build();
        }
        if (result.getType() == PlannerType.QUESTION) {
            List<String> stepSlots = stepSlots(result.getMissingSlots());
            String question = withInputTemplate(
                    firstNonBlank(result.getQuestion(), "Please provide the missing details to continue."),
                    stepSlots);
            QuestionSpec.QuestionSpecBuilder questionBuilder = QuestionSpec.builder()
                    .question(question)
                    .originalRequest(context == null ? null : context.getRawInput())
                    .blocking(true);
            if (stepSlots != null) {
                stepSlots.forEach(questionBuilder::expectedKey);
            }
            return TurnDecision.builder()
                    .type(TurnDecision.Type.QUESTION)
                    .question(questionBuilder.build())
                    .build();
        }
        if (result.getType() == PlannerType.PLAN) {
            String rawInput = context == null ? "" : firstNonBlank(context.getRawInput(), "");
            if (result.getRoute() == PlannerRoute.DIAGNOSE && isTemplateRequest(rawInput)) {
                return TurnDecision.builder()
                        .type(TurnDecision.Type.MESSAGE)
                        .message(buildTemplateResponse(rawInput, result.getAnswer()))
                        .build();
            }
            return TurnDecision.builder()
                    .type(TurnDecision.Type.ACTION)
                    .actionPlan(assembler.toActionPlan(result, context, context.getSession()))
                    .build();
        }
        return TurnDecision.builder()
                .type(TurnDecision.Type.MESSAGE)
                .message(firstNonBlank(result.getAnswer(), result.getQuestion(), "Planner failed to classify the request."))
                .build();
    }

    private String buildTemplateResponse(String rawInput, String llmHint) {
        if (exampleTemplateStore != null && !exampleTemplateStore.isEmpty()) {
            // Prefer LLM hint (example name) if provided, then fallback to query-based scoring
            String searchQuery = (llmHint != null && !llmHint.isBlank()) ? llmHint + " " + rawInput : rawInput;
            ExampleTemplate best = exampleTemplateStore.findBestMatch(searchQuery)
                    .orElse(exampleTemplateStore.getAll().get(0));
            return "以下是「" + best.getTitle() + "」的配置模板（" + best.getName() + ".yaml）：\n\n"
                    + best.getContent().trim()
                    + "\n\n可以用 `/use-config <path>` 加载已有配置文件，或直接描述你的比对目标让 AI 生成配置。";
        }
        return "可以，先给你一个可改的模板（YAML）：\n"
                + "source:\n"
                + "  type: mysql\n"
                + "  name: source-mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://HOST:3306/DB\n"
                + "    username: USER\n"
                + "    password: PASS\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: my_table\n"
                + "target:\n"
                + "  type: postgresql\n"
                + "  name: target-postgresql\n"
                + "  connection:\n"
                + "    url: jdbc:postgresql://HOST:5432/DB\n"
                + "    username: USER\n"
                + "    password: PASS\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: my_table\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source:\n"
                + "      - id\n"
                + "    target:\n"
                + "      - id\n"
                + "strategy:\n"
                + "  mode: checksum\n"
                + "result:\n"
                + "  sinks:\n"
                + "    - format: console\n"
                + "      type: result";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String withInputTemplate(String question, List<String> missingSlots) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return question + "\n\n请尽量按 `key=value` 格式回答，例如：`keys=id`。";
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String slot : missingSlots) {
            if (slot != null && !slot.isBlank()) {
                normalized.add(slot.trim());
            }
        }
        StringBuilder builder = new StringBuilder(question)
                .append("\n\n请按以下格式回复（可复制后直接填写）：\n")
                .append("```text\n");
        for (String slot : normalized) {
            builder.append(slotTemplate(slot)).append("\n");
        }
        builder.append("```\n")
                .append("你可以先填当前这一组，其他项留空，系统会继续追问下一组。\n")
                .append("示例：sourceType=mysql, targetType=postgresql, sourceTable=orders_detail, targetTable=orders_summary, keys=order_id");
        return builder.toString();
    }

    private List<String> stepSlots(List<String> missingSlots) {
        if (missingSlots == null || missingSlots.isEmpty()) {
            return List.of();
        }
        List<String> sourceGroup = new ArrayList<>();
        List<String> targetGroup = new ArrayList<>();
        List<String> keyGroup = new ArrayList<>();
        List<String> otherGroup = new ArrayList<>();
        for (String slot : missingSlots) {
            if (slot == null || slot.isBlank()) {
                continue;
            }
            String key = slot.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").trim();
            if (key.startsWith("source")) {
                sourceGroup.add(slot);
            } else if (key.startsWith("target")) {
                targetGroup.add(slot);
            } else if (key.contains("key")) {
                keyGroup.add(slot);
            } else {
                otherGroup.add(slot);
            }
        }
        if (!sourceGroup.isEmpty()) {
            return sourceGroup;
        }
        if (!targetGroup.isEmpty()) {
            return targetGroup;
        }
        if (!keyGroup.isEmpty()) {
            return keyGroup;
        }
        return otherGroup.isEmpty() ? missingSlots : otherGroup;
    }

    private String slotTemplate(String slot) {
        String key = slot.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").trim();
        if ("sourcetype".equals(key)) {
            return "sourceType=mysql";
        }
        if ("targettype".equals(key)) {
            return "targetType=postgresql";
        }
        if ("sourcetable".equals(key) || "sourceresource".equals(key)) {
            return "sourceTable=orders_detail  # 或 sourceQuery=SELECT ...";
        }
        if ("targettable".equals(key) || "targetresource".equals(key)) {
            return "targetTable=orders_summary  # 或 targetQuery=SELECT ...";
        }
        if ("sourcequery".equals(key)) {
            return "sourceQuery=SELECT ...";
        }
        if ("targetquery".equals(key)) {
            return "targetQuery=SELECT ...";
        }
        if ("keys".equals(key)) {
            return "keys=order_id  # 多列用逗号分隔，例如 keys=order_id,user_id";
        }
        if ("sourcekeys".equals(key)) {
            return "sourceKeys=order_id";
        }
        if ("targetkeys".equals(key)) {
            return "targetKeys=order_id";
        }
        return slot + "=<value>";
    }

    private boolean isTemplateRequest(String rawInput) {
        String text = rawInput == null ? "" : rawInput.toLowerCase();
        return text.contains("模板")
                || text.contains("模版")
                || text.contains("template")
                || text.contains("example")
                || text.contains("样例")
                || text.contains("示例")
                || text.contains("给我个配置")
                || text.contains("给个配置")
                || text.contains("帮我配置")
                || (text.contains("配置") && (text.contains("给") || text.contains("要一个") || text.contains("写一个")));
    }
}
