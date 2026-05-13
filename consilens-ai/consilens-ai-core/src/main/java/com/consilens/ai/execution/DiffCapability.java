package com.consilens.ai.execution;

import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DiffExecutionReport;
import com.consilens.ai.execution.model.LatestDiffPointer;

import java.util.Optional;

/**
 * Deterministic diff execution capability exposed to the AI runtime.
 */
public interface DiffCapability {

    DiffExecutionReport execute(ConfigRef configRef);

    Optional<LatestDiffPointer> latest(String sessionId);
}
