package com.consilens.ai.session.model;

import lombok.Builder;
import lombok.Value;

/**
 * Candidate memory extracted from a conversation turn.
 */
@Value
@Builder
public class AiMemoryCandidate {

    String type;
    String content;
    String source;
}
