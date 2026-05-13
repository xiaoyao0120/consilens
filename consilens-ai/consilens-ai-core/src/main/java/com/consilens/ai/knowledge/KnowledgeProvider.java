package com.consilens.ai.knowledge;

import com.consilens.ai.knowledge.model.KnowledgeChunk;
import com.consilens.ai.knowledge.model.KnowledgeQuery;

/**
 * Provides knowledge fragments to the AI runtime.
 */
public interface KnowledgeProvider {

    String name();

    KnowledgeChunk load(KnowledgeQuery query);
}
