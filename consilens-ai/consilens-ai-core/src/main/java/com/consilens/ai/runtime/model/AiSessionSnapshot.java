package com.consilens.ai.runtime.model;

import com.consilens.ai.session.model.AiSession;
import lombok.Builder;
import lombok.Value;

/**
 * Snapshot of runtime-visible session state.
 */
@Value
@Builder
public class AiSessionSnapshot {

    AiSession session;
    String latestArtifactId;
}
