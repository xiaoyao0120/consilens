package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.QuestionSpec;
import com.consilens.ai.session.model.PendingQuestionState;

/**
 * Owns clarification creation and answer merging.
 */
public interface ClarificationManager {

    PendingQuestionState create(QuestionSpec spec);

    String merge(PendingQuestionState pendingQuestion, String answer);
}
