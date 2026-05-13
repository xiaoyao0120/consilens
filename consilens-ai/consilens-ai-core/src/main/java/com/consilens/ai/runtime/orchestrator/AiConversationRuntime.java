package com.consilens.ai.runtime.orchestrator;

import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;

/**
 * Unified AI runtime entrypoint for interactive and command-driven flows.
 */
public interface AiConversationRuntime {

    AiTurnResult handleUserInput(String sessionId, String input);

    AiTurnResult executeCommand(String sessionId, AiTaskContext context);

    AiSessionSnapshot snapshot(String sessionId);
}
