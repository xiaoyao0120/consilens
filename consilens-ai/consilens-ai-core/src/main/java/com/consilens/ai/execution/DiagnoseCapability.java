package com.consilens.ai.execution;

import com.consilens.ai.execution.model.DiagnoseReport;
import com.consilens.ai.execution.model.EvidenceRef;

/**
 * Deterministic diagnosis capability exposed to the AI runtime.
 */
public interface DiagnoseCapability {

    DiagnoseReport diagnose(EvidenceRef evidenceRef);
}
