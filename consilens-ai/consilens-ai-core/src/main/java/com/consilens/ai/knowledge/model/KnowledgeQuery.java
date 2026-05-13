package com.consilens.ai.knowledge.model;

import lombok.Builder;
import lombok.Value;

/**
 * Query for loading knowledge into the AI runtime.
 */
@Value
@Builder
public class KnowledgeQuery {

    String topic;
    String scope;
    String keyword;
}
