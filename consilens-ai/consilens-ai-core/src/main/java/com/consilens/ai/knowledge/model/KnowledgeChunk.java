package com.consilens.ai.knowledge.model;

import lombok.Builder;
import lombok.Value;

/**
 * Knowledge block returned by a provider.
 */
@Value
@Builder
public class KnowledgeChunk {

    String provider;
    String title;
    String content;
}
