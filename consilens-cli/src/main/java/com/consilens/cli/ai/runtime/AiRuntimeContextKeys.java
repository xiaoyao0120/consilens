package com.consilens.cli.ai.runtime;

/**
 * Attribute keys carried inside {@code AiTaskContext}.
 */
public final class AiRuntimeContextKeys {

    public static final String CONFIG_REQUEST = "configRequest";
    public static final String OUTPUT_PATH = "outputPath";
    public static final String PERFORM_DRY_RUN = "performDryRun";
    public static final String APPROVE_EXECUTE = "approveExecute";
    public static final String APPROVAL_MODE = "approvalMode";
    public static final String EVIDENCE_PATH = "evidencePath";
    public static final String ANALYZER = "analyzer";
    public static final String CONFIG_PATH = "configPath";
    public static final String INLINE_OUTPUT = "inlineOutput";
    public static final String MEMORY_TYPE = "memoryType";
    public static final String MEMORY_CONTENT = "memoryContent";
    public static final String MEMORY_ID = "memoryId";

    private AiRuntimeContextKeys() {
    }
}
