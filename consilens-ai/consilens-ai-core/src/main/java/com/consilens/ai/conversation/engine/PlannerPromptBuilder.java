package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.session.model.AiSession;

/**
 * Builds the system and user prompts for the LLM-backed planner.
 */
public class PlannerPromptBuilder {

    private final ExampleTemplateStore exampleTemplateStore;

    public PlannerPromptBuilder() {
        this(new ExampleTemplateStore());
    }

    public PlannerPromptBuilder(ExampleTemplateStore exampleTemplateStore) {
        this.exampleTemplateStore = exampleTemplateStore;
    }

    public String buildSystemPrompt(PlannerContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the Planner Agent for Consilens AI.\n")
                .append("\n")
                .append("Your job is NOT to generate YAML directly.\n")
                .append("Your job is to decide whether the user's message should go to:\n")
                .append("1. PLAN  - create or modify a compare config\n")
                .append("2. CHAT  - answer a question, explain, troubleshoot, diagnose logs/errors\n")
                .append("3. QUESTION - ask for missing CRITICAL compare information before planning\n")
                .append("4. ERROR - only when the request is malformed or unsafe\n")
                .append("\n")
                .append("Rules:\n")
                .append("- Output PLAN only when the user provides (or you can extract from context) ALL critical slots: sourceType, targetType, source resource, target resource, compare keys.\n")
                .append("- NEVER skip QUESTION to output PLAN directly. If ANY critical slot is missing, ask via QUESTION.\n")
                .append("- CRITICAL RULE: If the message contains a DATA comparison intent (e.g. '我要比对', '比较数据', '对比', 'compare data', 'diff between tables') COMBINED WITH a configuration question ('怎么配置', 'how to configure', '如何设置'), but critical slots are missing, output QUESTION, NOT PLAN.\n")
                .append("- Output CHAT for greetings, help, conceptual questions, connector comparisons, why/how questions (WITHOUT a compare-data intent), log/error explanation, troubleshooting, and config explanation.\n")
                .append("- Output CHAT when the user asks for a template/example/sample (e.g. 模板/模版/template/example/给我个模板/给个配置/能给模板) instead of executing a task.\n")
                .append("  When routing as CHAT for a template request, set answer to the example name only (e.g. 'minimal-mysql-to-pg').\n")
                .append("  Do NOT generate YAML yourself — the system will fetch the actual example file from the examples directory.\n")
                .append("- If the user is asking about differences BETWEEN technologies/connectors/tools, that is CHAT, not PLAN.\n")
                .append("- If the user is asking to compare DATA between source and target systems/tables/sql WITH all critical slots known, that is PLAN.\n")
                .append("- If the user is questioning planner behavior, asking why the current session failed, or asking how the system should work, that is CHAT.\n")
                .append("- Prefer CHAT over QUESTION whenever the user is discussing the system itself rather than requesting a config action.\n")
                .append("- For DIAGNOSE/REPAIR routes, only output PLAN when there is concrete evidence context (run/diagnosis artifacts) and the user explicitly asks to diagnose or repair.\n")
                .append("\n")
                .append("Critical slots for compare planning (ALWAYS ask for missing ones via QUESTION, don't assume):\n")
                .append("- sourceType (e.g., mysql, postgresql, oracle) - REQUIRED before planning\n")
                .append("- targetType (e.g., mysql, postgresql, oracle) - REQUIRED before planning\n")
                .append("- source resource (specific table name or SQL query) - REQUIRED before planning\n")
                .append("- target resource (specific table name or SQL query) - REQUIRED before planning\n")
                .append("- compare keys (which columns uniquely identify rows) - REQUIRED before planning\n")
                .append("- mapping only when the source/target fields are explicitly heterogeneous\n")
                .append("\n")
                .append("ROUTING STRATEGY:\n")
                .append("- If the user provides a compare intent but is missing ANY critical slot, output QUESTION, NOT PLAN.\n")
                .append("- Ask ALL missing slots in ONE question, grouped logically (source info together, target info together, etc).\n")
                .append("- Be specific: e.g., 'What is the source database type?' not 'Tell me source info'.\n")
                .append("- After the user answers, you will get another turn to evaluate if all critical info is now available.\n")
                .append("\n")
                .append("Do NOT interrupt the user for these non-critical items (default them in PLAN):\n")
                .append("- strategyMode (default: checksum)\n")
                .append("- algorithm (default: xor)\n")
                .append("- maxDifferences (default: no limit)\n")
                .append("- result sink format (default: console)\n")
                .append("- field projections (assume all fields unless user specified)\n")
                .append("\n")
                .append("When you output QUESTION:\n")
                .append("- Summarize what you understood so far in 1-2 sentences.\n")
                .append("- Ask for ALL missing critical information in ONE focused question.\n")
                .append("- In missingSlots, use canonical keys from this set when possible: sourceType, targetType, sourceTable, targetTable, sourceQuery, targetQuery, keys, sourceKeys, targetKeys.\n")
                .append("- Ask users to answer in `key=value` format so the next turn can be parsed deterministically.\n")
                .append("- Provide helpful hints (e.g., 'source and target can be different databases').\n")
                .append("- Keep tone conversational and specific to the user's context.\n")
                .append("\n")
                .append("Before deciding, you may call tools to:\n")
                .append("- inspect config schema\n")
                .append("- inspect session state\n")
                .append("- inspect current config\n")
                .append("- inspect memory facts\n")
                .append("- extract deterministic compare hints\n")
                .append("- inspect supported connectors\n")
                .append("- use these tools before asking a clarification question when the user intent is already mostly clear\n")
                .append("\n");

        if (exampleTemplateStore != null && !exampleTemplateStore.isEmpty()) {
            sb.append(exampleTemplateStore.buildSummaryForPrompt()).append("\n\n");
        }

        sb.append("Return JSON only. No markdown. No prose outside JSON.\n")
                .append("\n")
                .append("JSON schema:\n")
                .append("{\n")
                .append("  \"type\": \"PLAN|CHAT|QUESTION|ERROR\",\n")
                .append("  \"route\": \"PLAN_CONFIG|MODIFY_CONFIG|RUN_DIFF|DIAGNOSE|REPAIR|CHAT\",\n")
                .append("  \"normalizedGoal\": \"string\",\n")
                .append("  \"extractedSlots\": {},\n")
                .append("  \"missingSlots\": [],\n")
                .append("  \"assumptions\": [],\n")
                .append("  \"question\": \"string or null\",\n")
                .append("  \"answer\": \"string or null\",\n")
                .append("  \"reasoning\": \"string\"\n")
                .append("}");
        return sb.toString();
    }

    public String buildUserPrompt(PlannerContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("Current request:\n");
        builder.append(nullToEmpty(context == null ? null : context.getRawInput())).append("\n");
        builder.append("\nSession summary:\n");
        appendSession(builder, context == null ? null : context.getSession());
        if (context != null && context.getCommandName() != null && !context.getCommandName().isBlank()) {
            builder.append("\nCommand:\n")
                    .append("name=").append(context.getCommandName()).append("\n")
                    .append("argument=").append(nullToEmpty(context.getCommandArgument())).append("\n");
        }
        if (context != null && context.getAttributes() != null && !context.getAttributes().isEmpty()) {
            builder.append("\nAttributes:\n");
            context.getAttributes().forEach((key, value) -> builder
                    .append(key)
                    .append("=")
                    .append(value == null ? "" : value)
                    .append("\n"));
        }
        return builder.toString().trim();
    }

    private void appendSession(StringBuilder builder, AiSession session) {
        if (session == null) {
            builder.append("status=unknown\n");
            return;
        }
        builder.append("sessionId=").append(nullToEmpty(session.getSessionId())).append("\n")
                .append("status=").append(nullToEmpty(session.getStatus())).append("\n")
                .append("currentTask=").append(nullToEmpty(session.getCurrentTask())).append("\n")
                .append("currentObjective=").append(nullToEmpty(session.getCurrentObjective())).append("\n")
                .append("currentConfigArtifactId=").append(nullToEmpty(session.getCurrentConfigArtifactId())).append("\n")
                .append("latestRunArtifactId=").append(nullToEmpty(session.getLatestRunArtifactId())).append("\n")
                .append("latestDiagnosisArtifactId=").append(nullToEmpty(session.getLatestDiagnosisArtifactId())).append("\n")
                .append("latestAuditArtifactId=").append(nullToEmpty(session.getLatestAuditArtifactId())).append("\n");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
