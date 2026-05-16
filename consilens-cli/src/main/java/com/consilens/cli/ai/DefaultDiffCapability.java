package com.consilens.cli.ai;

import com.consilens.ai.execution.DiffCapability;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DiffExecutionReport;
import com.consilens.ai.execution.model.EvidenceRef;
import com.consilens.ai.execution.model.LatestDiffPointer;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import com.consilens.cli.ai.runtime.AiRuntimePaths;
import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.service.DiffService;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CLI-backed deterministic diff capability that persists result/evidence artifacts.
 */
public class DefaultDiffCapability implements DiffCapability {

    private final ConfigurationManager configurationManager;
    private final DiffService diffService;
    private final AiArtifactStore artifactStore;
    private final AiRuntimePaths runtimePaths;
    private final ObjectMapper objectMapper;

    public DefaultDiffCapability(AiArtifactStore artifactStore, AiRuntimePaths runtimePaths) {
        this(new ConfigurationManager(), new DiffService(), artifactStore, runtimePaths, new ObjectMapper());
    }

    DefaultDiffCapability(ConfigurationManager configurationManager,
                          DiffService diffService,
                          AiArtifactStore artifactStore,
                          AiRuntimePaths runtimePaths,
                          ObjectMapper objectMapper) {
        this.configurationManager = configurationManager;
        this.diffService = diffService;
        this.artifactStore = artifactStore;
        this.runtimePaths = runtimePaths;
        this.objectMapper = objectMapper;
    }

    @Override
    public DiffExecutionReport execute(ConfigRef configRef) {
        try {
            String runId = "run-" + UUID.randomUUID();
            CliConfiguration config = configurationManager.loadConfiguration(
                    new ByteArrayInputStream(configRef.getContent().getBytes(StandardCharsets.UTF_8)), "yaml");
            Path runDir = runtimePaths.sessionRunDir(configRef.getSessionId());
            Files.createDirectories(runDir);
            Path evidencePath = runDir.resolve(runId + "-diff-records.json");
            configureEvidenceSink(config, evidencePath);

            CliDiffResult result = diffService.performDiff(config);
            String resultJson = objectMapper.writeValueAsString(result);
            ArtifactRef resultArtifact = artifactStore.write(
                    configRef.getSessionId(),
                    ArtifactType.DIFF_RESULT,
                    resultJson.getBytes(StandardCharsets.UTF_8),
                    Map.of("runId", runId,
                            "kind", "diff-result",
                            "configArtifactId", configRef.getArtifactId() == null ? "" : configRef.getArtifactId()));

            byte[] evidenceBytes = Files.exists(evidencePath)
                    ? Files.readAllBytes(evidencePath)
                    : "[]".getBytes(StandardCharsets.UTF_8);
            ArtifactRef evidenceArtifact = artifactStore.write(
                    configRef.getSessionId(),
                    ArtifactType.DIFF_EVIDENCE,
                    evidenceBytes,
                    Map.of("runId", runId,
                            "kind", "diff-evidence",
                            "configArtifactId", configRef.getArtifactId() == null ? "" : configRef.getArtifactId(),
                            "resultArtifactId", resultArtifact.getArtifactId()));

            LatestDiffPointer pointer = LatestDiffPointer.builder()
                    .sessionId(configRef.getSessionId())
                    .runId(runId)
                    .resultArtifactId(resultArtifact.getArtifactId())
                    .evidenceArtifactId(evidenceArtifact.getArtifactId())
                    .build();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    runtimePaths.latestRunFile(configRef.getSessionId()).toFile(), pointer);

            return DiffExecutionReport.builder()
                    .sessionId(configRef.getSessionId())
                    .runId(runId)
                    .success(true)
                    .summary(summary(result))
                    .resultArtifactId(resultArtifact.getArtifactId())
                    .evidenceRef(EvidenceRef.builder()
                            .sessionId(configRef.getSessionId())
                            .artifactId(evidenceArtifact.getArtifactId())
                            .path(evidenceArtifact.getPath())
                            .build())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("AI diff execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<LatestDiffPointer> latest(String sessionId) {
        Path latestFile = runtimePaths.latestRunFile(sessionId);
        if (!Files.exists(latestFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(latestFile.toFile(), LatestDiffPointer.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read latest AI diff pointer for session " + sessionId, e);
        }
    }

    private void configureEvidenceSink(CliConfiguration config, Path evidencePath) throws Exception {
        Files.createDirectories(evidencePath.getParent());
        if (config.getResult() == null) {
            config.setResult(new ResultConfig());
        }
        if (config.getResult().getSinks() == null) {
            config.getResult().setSinks(new ArrayList<>());
        }
        List<SinkConfig> sinks = config.getResult().getSinks();
        SinkConfig evidenceSink = sinks.stream()
                .filter(sink -> "json".equalsIgnoreCase(sink.getFormat()) && "diff-record".equalsIgnoreCase(sink.getType()))
                .findFirst()
                .orElseGet(() -> {
                    SinkConfig sink = new SinkConfig();
                    sink.setFormat("json");
                    sink.setType("diff-record");
                    sinks.add(sink);
                    return sink;
                });
        evidenceSink.setProperties(objectMapper.writeValueAsString(Map.of(
                "path", evidencePath.toString(),
                "pretty", true
        )));
    }

    private String summary(CliDiffResult result) {
        return "totalDifferences=" + result.getTotalDifferences()
                + ", sourceMissing=" + result.getSourceMissingCount()
                + ", targetMissing=" + result.getTargetMissingCount()
                + ", mismatch=" + result.getMismatchCount();
    }
}
