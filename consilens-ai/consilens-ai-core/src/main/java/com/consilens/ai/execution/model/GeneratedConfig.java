package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

/**
 * Generated config plus runtime-facing assumptions and environment requirements.
 */
@Value
@Builder
public class GeneratedConfig {

    ConfigRef configRef;
    @Singular("assumption")
    List<String> assumptions;
    @Singular("requiredEnv")
    List<String> requiredEnvs;
}
