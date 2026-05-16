package com.consilens.ai.runtime.intent;

import com.consilens.ai.chat.IntentParser;
import com.consilens.ai.model.Intent;
import com.consilens.ai.session.model.AiSession;

import java.util.Locale;

/**
 * Default intent router that bridges the existing keyword-based parser into the new runtime intents.
 */
public class DefaultIntentRouter implements IntentRouter {

    private final IntentParser parser;

    public DefaultIntentRouter() {
        this(new IntentParser());
    }

    public DefaultIntentRouter(IntentParser parser) {
        this.parser = parser;
    }

    @Override
    public AiIntent route(AiSession session, String userInput) {
        String normalized = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (looksLikeConnectorQuestion(normalized)) {
            return AiIntent.GENERAL_QA;
        }
        if (CompareIntentHintExtractor.looksLikeComparePlan(userInput)) {
            return hasConfig(session) ? AiIntent.RUN_DIFF : AiIntent.PLAN_CONFIG;
        }
        if (containsAny(normalized, "repair sql", "fix sql", "rollback sql", "修复 sql", "修复sql")) {
            return AiIntent.REPAIR_CONFIG;
        }
        if (containsAny(normalized,
                "normalization", "normalize", "normalized", "timezone", "time zone",
                "filter", "filters", "where ", "join ", "full outer join", "left join", "right join",
                "fields", "field ", "columns", "column ", "projection", "select ",
                "trim", "lower(", "upper(", "case insensitive", "ignore case",
                "null as", "whitespace", "deduplicate", "dedup", "mapping", "字段", "过滤")) {
            return hasConfig(session) ? AiIntent.MODIFY_CONFIG : AiIntent.PLAN_CONFIG;
        }
        Intent intent = parser.parse(userInput);
        switch (intent) {
            case GENERATE_CONFIG:
                return hasConfig(session) ? AiIntent.MODIFY_CONFIG : AiIntent.PLAN_CONFIG;
            case DIFF_TABLE:
                return looksLikeComparisonQuestion(normalized)
                        ? AiIntent.GENERAL_QA
                        : (hasConfig(session) ? AiIntent.RUN_DIFF : AiIntent.PLAN_CONFIG);
            case EXPLAIN_RESULT:
                return hasConfig(session) ? AiIntent.EXPLAIN_CONFIG : AiIntent.DIAGNOSE_RESULT;
            case SUGGEST_REPAIR:
                return AiIntent.REPAIR_CONFIG;
            case SHOW_HELP:
            case DISCOVER_SCHEMA:
            case GENERAL_CHAT:
            case UNKNOWN:
            default:
                return AiIntent.GENERAL_QA;
        }
    }

    private boolean hasConfig(AiSession session) {
        return session != null
                && session.getCurrentConfigArtifactId() != null
                && !session.getCurrentConfigArtifactId().trim().isEmpty();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeConnectorQuestion(String normalized) {
        return containsAny(normalized,
                "有什么区别", "区别是什么", "difference between", "what is the difference",
                "哪个好", "which is better", "优缺点", "什么时候用", "when should i use");
    }

    private boolean looksLikeComparisonQuestion(String normalized) {
        return looksLikeConnectorQuestion(normalized)
                || containsAny(normalized, "为什么", "why ", "怎么", "how ", "what ", "tell me");
    }
}
