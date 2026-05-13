package com.consilens.ai.execution;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.ExplainReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;

/**
 * Deterministic config-related execution capabilities exposed to the AI runtime.
 */
public interface ConfigCapability {

    GeneratedConfig generate(ConfigGenerationRequest request);

    ValidationReport validate(ConfigRef configRef);

    DryRunReport dryRun(ConfigRef configRef);

    ExplainReport explain(ConfigRef configRef);
}
