package com.consilens.ai.session;

import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiMemoryCandidate;

import java.util.List;
import java.util.Optional;

/**
 * Store for non-sensitive persistent AI memories.
 */
public interface AiMemoryStore {

    List<AiMemory> list();

    Optional<AiMemory> add(AiMemoryCandidate candidate);

    void remove(String memoryId);
}
