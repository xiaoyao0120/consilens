package com.consilens.cli.ai;

import com.consilens.ai.execution.DiagnoseCapability;
import com.consilens.ai.execution.model.DiagnoseReport;
import com.consilens.ai.execution.model.EvidenceRef;
import com.consilens.ai.model.AnalysisResult;
import com.consilens.ai.model.PatternMatch;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.ai.spi.AIAnalyzerManager;

/**
 * CLI-backed deterministic diagnose capability.
 */
public class DefaultDiagnoseCapability implements DiagnoseCapability {

    private static final String DEFAULT_ANALYZER = "rulebased";

    private final AIDiagnoseService diagnoseService;
    private final AIAnalyzer analyzer;

    public DefaultDiagnoseCapability(String analyzerName) {
        this(resolveAnalyzer(analyzerName));
    }

    DefaultDiagnoseCapability(AIAnalyzer analyzer) {
        this.analyzer = analyzer;
        this.diagnoseService = new AIDiagnoseService(analyzer);
    }

    @Override
    public DiagnoseReport diagnose(EvidenceRef evidenceRef) {
        try {
            AnalysisResult analysis = analyzer.analyze(diagnoseService.loadResult(evidenceRef.getPath()));
            DiagnoseReport.DiagnoseReportBuilder builder = DiagnoseReport.builder()
                    .summary(analysis.getSummary());
            if (analysis.getPatterns() != null) {
                for (PatternMatch pattern : analysis.getPatterns()) {
                    builder.pattern(pattern.getPatternName() + ": " + pattern.getDescription());
                    if (pattern.getRepairHint() != null && !pattern.getRepairHint().isBlank()) {
                        builder.repairHint(pattern.getRepairHint());
                    }
                }
            }
            if (analysis.getRepairHints() != null) {
                analysis.getRepairHints().forEach(builder::repairHint);
            }
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("AI diagnose failed: " + e.getMessage(), e);
        }
    }

    private static AIAnalyzer resolveAnalyzer(String analyzerName) {
        String selected = analyzerName;
        if (selected == null || selected.trim().isEmpty()) {
            selected = System.getenv("CONSILENS_AI_ANALYZER");
        }
        if (selected == null || selected.trim().isEmpty()) {
            selected = DEFAULT_ANALYZER;
        }
        return AIAnalyzerManager.getInstance().create(selected.trim());
    }
}
