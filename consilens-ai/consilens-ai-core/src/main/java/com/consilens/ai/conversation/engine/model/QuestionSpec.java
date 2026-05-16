package com.consilens.ai.conversation.engine.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Clarification question emitted by the planner.
 */
@Value
@Builder
public class QuestionSpec {

    String question;
    String originalRequest;
    @Singular("expectedKey")
    List<String> expectedKeys;
    boolean blocking;
}
