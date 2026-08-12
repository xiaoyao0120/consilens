package com.consilens.server.application.task.impl;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskDiffSummaryDto;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.application.diff.DiffReportSupport;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskPage;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RunTaskQueryServiceImpl implements RunTaskQueryService {

    /**
     * 列表差异摘要在该大小以下才读取 content 解析（避免对大 RUN_RESULT 全量 IO）。
     */
    private static final long MAX_SUMMARY_CONTENT_BYTES = 2L * 1024 * 1024;

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final TaskDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    public RunTaskQueryServiceImpl(TaskRepository taskRepository,
                                   ArtifactRepository artifactRepository,
                                   ArtifactContentStore artifactContentStore,
                                   TaskDefinitionRepository definitionRepository,
                                   ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskQueryResponse getTask(String taskId, String traceId) {
        TaskInstanceRecord task = taskRepository.resolveByRef(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        String definitionName = task.getDefinitionId() != null
                ? definitionRepository.findById(task.getDefinitionId()).map(TaskDefinitionRecord::getName).orElse(null)
                : null;
        return TaskQueryResponse.builder()
                .taskId(task.getInstanceKey())
                .serialNo(task.getSerialNo())
                .definitionId(task.getDefinitionId() != null ? String.valueOf(task.getDefinitionId()) : null)
                .definitionName(definitionName)
                .taskType("RUN")
                .status(task.getStatus().name())
                .traceId(task.getTraceId())
                .executeNodeKey(task.getExecuteNodeKey())
                .retryCount(task.getRetryCount())
                .submitTime(task.getSubmitTime())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .artifacts(artifactRepository.listByTaskId(task.getId()).stream()
                        .map(this::toArtifactRef)
                        .collect(Collectors.toList()))
                .availableNextActions(nextActions(task))
                .build();
    }

    @Override
    public PageResponse<TaskSummaryDto> listTasks(int page,
                                                  int pageSize,
                                                  Collection<TaskStatus> statuses,
                                                  String keyword,
                                                  String executeNodeKey,
                                                  Instant startTime,
                                                  Instant endTime,
                                                  boolean includeDiffSummary,
                                                  Long definitionId,
                                                  String traceId) {
        TaskPage taskPage = taskRepository.listTaskPage(page,
                pageSize,
                statuses,
                keyword,
                executeNodeKey,
                startTime,
                endTime,
                definitionId);
        List<TaskInstanceRecord> items = taskPage.getItems();
        Map<Long, String> definitionNames = definitionRepository.findNamesByIds(items.stream()
                .map(TaskInstanceRecord::getDefinitionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        List<TaskSummaryDto> dtoItems = items.stream()
                .map(task -> toSummary(task, includeDiffSummary, definitionNames))
                .collect(Collectors.toList());
        return PageResponse.<TaskSummaryDto>builder()
                .total(taskPage.getTotal())
                .page(page)
                .pageSize(pageSize)
                .items(dtoItems)
                .build();
    }

    private TaskSummaryDto toSummary(TaskInstanceRecord task, boolean includeDiffSummary,
                                     Map<Long, String> definitionNames) {
        TaskDiffSummaryDto diffSummary = null;
        if (includeDiffSummary && task.getStatus() == TaskStatus.SUCCEEDED) {
            diffSummary = readDiffSummary(task);
        }
        return TaskSummaryDto.builder()
                .taskId(task.getInstanceKey())
                .id(String.valueOf(task.getId()))
                .serialNo(task.getSerialNo())
                .taskType("RUN")
                .status(task.getStatus().name())
                .traceId(task.getTraceId())
                .executeNodeKey(task.getExecuteNodeKey())
                .retryCount(task.getRetryCount())
                .submitTime(task.getSubmitTime())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .availableNextActions(nextActions(task))
                .diffSummary(diffSummary)
                .definitionId(task.getDefinitionId() != null ? String.valueOf(task.getDefinitionId()) : null)
                .definitionName(task.getDefinitionId() != null
                        ? definitionNames.get(task.getDefinitionId()) : null)
                .build();
    }

    private TaskDiffSummaryDto readDiffSummary(TaskInstanceRecord task) {
        try {
            ArtifactRecord runResult = latestRunResult(task.getId());
            if (runResult == null || runResult.getStorageUri() == null) {
                return null;
            }
            // 列表场景避免全量读取超大 content：超过阈值仅返回 null（摘要可空降级）
            if (artifactContentStore.size(runResult.getStorageUri()) > MAX_SUMMARY_CONTENT_BYTES) {
                return null;
            }
            JsonNode root = objectMapper.readTree(artifactContentStore.read(runResult.getStorageUri()));
            if (root.path("success").isBoolean() && !root.path("success").asBoolean()) {
                return null;
            }
            JsonNode statistics = root.get("statistics");
            long totalDifferenceCount;
            if (statistics != null && statistics.isObject()
                    && statistics.path("totalDifferences").isNumber()) {
                totalDifferenceCount = statistics.path("totalDifferences").asLong();
            } else {
                JsonNode topLevel = root.get("differenceCount");
                if (topLevel == null || !topLevel.isNumber()) {
                    return null;
                }
                totalDifferenceCount = topLevel.asLong();
            }
            Double differencePercentage = statistics != null
                    && statistics.path("differencePercentage").isNumber()
                    ? statistics.path("differencePercentage").asDouble()
                    : null;
            // 注意：与 diff-report 链路不同，列表摘要不做 percentageFromCounts 回退——
            // 无 percentage 且差异>0 时 status 为 NONE（保持列表轻量），属有意设计
            Double matchScore = DiffReportSupport.matchScoreOf(differencePercentage, totalDifferenceCount);
            return TaskDiffSummaryDto.builder()
                    .differenceCount(totalDifferenceCount)
                    .differencePercentage(differencePercentage)
                    .status(DiffReportSupport.statusOf(matchScore, totalDifferenceCount))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private ArtifactRecord latestRunResult(Long taskId) {
        return artifactRepository.listByTaskId(taskId).stream()
                .filter(r -> r.getArtifactType() == ArtifactKind.RUN_RESULT)
                .max(Comparator.comparing(ArtifactRecord::getCreatedAt))
                .orElse(null);
    }

    private ArtifactRefDto toArtifactRef(ArtifactRecord record) {
        return ArtifactRefDto.builder()
                .id(record.getId())
                .type(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .build();
    }

    private List<String> nextActions(TaskInstanceRecord task) {
        if (task.getStatus() == TaskStatus.SUCCEEDED) {
            return List.of("diagnose", "repair");
        }
        if (task.getStatus() == TaskStatus.FAILED
                || task.getStatus() == TaskStatus.RETRYABLE
                || task.getStatus() == TaskStatus.CANCELLED) {
            return List.of("retry");
        }
        if (task.getStatus() == TaskStatus.PENDING
                || task.getStatus() == TaskStatus.CLAIMED
                || task.getStatus() == TaskStatus.RUNNING) {
            return List.of("cancel");
        }
        return List.of();
    }
}
