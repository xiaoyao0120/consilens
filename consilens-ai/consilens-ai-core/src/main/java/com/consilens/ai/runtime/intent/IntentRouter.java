package com.consilens.ai.runtime.intent;

import com.consilens.ai.session.model.AiSession;

/**
 * Routes user input to a runtime-level intent.
 */
public interface IntentRouter {

    AiIntent route(AiSession session, String userInput);
}
