package com.consilens.ai.runtime.intent;

import com.consilens.ai.chat.IntentParser;
import com.consilens.ai.model.Intent;
import com.consilens.ai.session.model.AiSession;

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
        Intent intent = parser.parse(userInput);
        switch (intent) {
            case GENERATE_CONFIG:
                return hasConfig(session) ? AiIntent.MODIFY_CONFIG : AiIntent.PLAN_CONFIG;
            case DIFF_TABLE:
                return AiIntent.RUN_DIFF;
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
}
