package com.consilens.server.application.capability;

import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.domain.enumtype.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RunTaskHandler implements CapabilityHandler<RunRequest> {

    private final ArtifactService artifactService;
    private final ServerCompareConfigService configService;

    public RunTaskHandler(ArtifactService artifactService, ServerCompareConfigService configService) {
        this.artifactService = artifactService;
        this.configService = configService;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.RUN;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, RunRequest request) {
        Instant startedAt = Instant.now();
        try {
            ServerCompareConfig config = configService.fromArtifact(request.getConfigArtifactId());
            if (Boolean.TRUE.equals(options(request).getDryRun())) {
                return writeSuccess(context, request, startedAt, true, configService.validateContent(config));
            }
            CompareRequest compareRequest = configService.toCompareRequest(config, options(request));
            DiffResult diffResult = new DefaultCompareRuntime().execute(compareRequest);
            return writeSuccess(context, request, startedAt, false, runResult(diffResult));
        } catch (InvalidInputException exception) {
            ArtifactRefDto artifact = writeFailure(context, request, startedAt, "INVALID_INPUT", exception.getMessage());
            throw new CapabilityExecutionException("INVALID_INPUT", exception.getMessage(), artifact.getId(), false, exception);
        } catch (Exception exception) {
            ArtifactRefDto artifact = writeFailure(context, request, startedAt, "RUN_EXECUTION_ERROR", exception.getMessage());
            throw new CapabilityExecutionException("RUN_EXECUTION_ERROR", exception.getMessage(), artifact.getId(), true, exception);
        }
    }

    private TaskExecutionResult writeSuccess(TaskExecutionContext context,
                                             RunRequest request,
                                             Instant startedAt,
                                             boolean dryRun,
                                             Map<String, Object> details) {
        Map<String, Object> content = baseContent(request, startedAt);
        content.put("success", true);
        content.put("dryRun", dryRun);
        content.putAll(details);
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.RUN_RESULT,
                "json",
                content,
                Map.of("source", "run"));
        return CapabilityResultSupport.result(artifact);
    }

    private ArtifactRefDto writeFailure(TaskExecutionContext context,
                                        RunRequest request,
                                        Instant startedAt,
                                        String errorCode,
                                        String errorMessage) {
        Map<String, Object> content = baseContent(request, startedAt);
        content.put("success", false);
        content.put("dryRun", Boolean.TRUE.equals(options(request).getDryRun()));
        content.put("errorCode", errorCode);
        content.put("errorMessage", errorMessage);
        return artifactService.writeArtifact(context,
                ArtifactKind.RUN_RESULT,
                "json",
                content,
                Map.of("source", "run", "errorCode", errorCode));
    }

    private Map<String, Object> baseContent(RunRequest request, Instant startedAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("serialNo", request.getSerialNo());
        content.put("configArtifactId", request.getConfigArtifactId());
        content.put("startedAt", startedAt.toString());
        content.put("completedAt", Instant.now().toString());
        return content;
    }

    private Map<String, Object> runResult(DiffResult diffResult) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("hasDifferences", diffResult != null && diffResult.hasDifferences());
        content.put("differenceCount", diffResult != null ? diffResult.getDifferenceCount() : 0);
        content.put("statistics", diffResult != null ? diffResult.getStatisticsMap() : Map.of());
        content.put("metadata", diffResult != null ? diffResult.getMetadata() : Map.of());
        content.put("differences", diffResult != null && diffResult.getDifferences() != null
                ? diffResult.getDifferences().stream().map(this::diffRow).collect(Collectors.toList())
                : List.of());
        return content;
    }

    Map<String, Object> diffRow(DiffRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", row.getOperation().name());
        result.put("primaryKey", row.getPrimaryKey());
        result.put("metadata", row.getMetadata());
        return result;
    }

    private RunRequest.Options options(RunRequest request) {
        return request.getOptions() != null ? request.getOptions() : new RunRequest.Options();
    }
}
