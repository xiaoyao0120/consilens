package com.consilens.server.application.diff;

import com.consilens.server.api.dto.DiffReportDto;
import com.consilens.server.application.artifact.ArtifactQueryService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation reading the latest RUN_RESULT artifact of a succeeded task.
 */
@Service
public class DiffReportServiceImpl implements DiffReportService {

    private static final String OPERATION_MISMATCH = "MISMATCH";

    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final ArtifactQueryService artifactQueryService;
    private final ObjectMapper objectMapper;

    public DiffReportServiceImpl(TaskRepository taskRepository,
                                 ArtifactRepository artifactRepository,
                                 ArtifactContentStore artifactContentStore,
                                 ArtifactQueryService artifactQueryService,
                                 ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.artifactQueryService = artifactQueryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public DiffReportDto getDiffReport(String taskId) {
        TaskInstanceRecord task = taskRepository.resolveByRef(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskId));
        if (task.getStatus() != TaskStatus.SUCCEEDED) {
            return emptyReport(taskId);
        }
        ArtifactRecord runResult = latestRunResult(task.getId());
        if (runResult == null) {
            return emptyReport(taskId);
        }
        // 统计优先读 DB（statistics_json）；旧格式回退读 content
        JsonNode statistics = statisticsNode(runResult);
        JsonNode differences = differencesNode(runResult);

        long totalDifferenceCount = longValue(statistics, "totalDifferences", -1L);
        if (totalDifferenceCount < 0 && runResult.getDifferenceCount() != null) {
            totalDifferenceCount = runResult.getDifferenceCount();
        }
        if (totalDifferenceCount < 0) {
            // 旧格式/无统计时兜底 content 顶层 differenceCount
            totalDifferenceCount = longValue(contentRootNode(runResult), "differenceCount", 0L);
        }
        Double differencePercentage = doubleValue(statistics, "differencePercentage");
        if (differencePercentage == null && statistics != null && statistics.isObject()) {
            differencePercentage = DiffReportSupport.percentageFromCounts(
                    totalDifferenceCount,
                    statistics.path("sourceRowCount").asLong(0),
                    statistics.path("targetRowCount").asLong(0));
        }
        Double matchScore = DiffReportSupport.matchScoreOf(differencePercentage, totalDifferenceCount);

        return DiffReportDto.builder()
                .taskId(taskId)
                .status(DiffReportSupport.statusOf(matchScore, totalDifferenceCount))
                .matchScore(matchScore)
                .statistics(toMap(statistics))
                .columns(buildColumns(differences))
                .samples(buildSamples(differences))
                .sampleSize((int) Math.min(100, totalDifferenceCount))
                .totalDifferenceCount(totalDifferenceCount)
                .sampleTruncated(Boolean.TRUE.equals(runResult.getDifferenceTruncated())
                        || totalDifferenceCount > 100)
                .timeline(List.of())
                .build();
    }

    private DiffReportDto emptyReport(String taskId) {
        return DiffReportDto.builder()
                .taskId(taskId)
                .status(DiffReportSupport.STATUS_NONE)
                .matchScore(null)
                .statistics(null)
                .columns(List.of())
                .samples(List.of())
                .sampleSize(null)
                .totalDifferenceCount(null)
                .sampleTruncated(false)
                .timeline(List.of())
                .build();
    }

    /**
     * 统计来源：新格式优先 DB statistics_json；旧格式从 content 解析。
     */
    private JsonNode contentRootNode(ArtifactRecord record) {
        try {
            return objectMapper.readTree(artifactContentStore.read(record.getStorageUri()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode statisticsNode(ArtifactRecord record) {
        if (record.getStatisticsJson() != null && !record.getStatisticsJson().isBlank()) {
            try {
                return objectMapper.readTree(record.getStatisticsJson());
            } catch (Exception ignored) {
                // fall through to content
            }
        }
        try {
            return objectMapper.readTree(artifactContentStore.read(record.getStorageUri())).get("statistics");
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 差异样本：新格式从差异文件分页读第一页；旧格式从 content.differences。
     */
    private JsonNode differencesNode(ArtifactRecord record) {
        if (record.getDifferencesUri() != null && !record.getDifferencesUri().isBlank()) {
            try {
                return objectMapper.valueToTree(artifactQueryService
                        .listDifferences(record.getId(), 0, 100, null, "diff-report").getItems());
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            return objectMapper.readTree(artifactContentStore.read(record.getStorageUri())).get("differences");
        } catch (Exception ignored) {
            return null;
        }
    }

    private ArtifactRecord latestRunResult(Long taskId) {
        return artifactRepository.listByTaskId(taskId).stream()
                .filter(r -> r.getArtifactType() == ArtifactKind.RUN_RESULT)
                .max(Comparator.comparing(ArtifactRecord::getCreatedAt))
                .orElse(null);
    }

    private List<DiffReportDto.ColumnStat> buildColumns(JsonNode differences) {
        if (differences == null || !differences.isArray()) {
            return List.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        long mismatchRows = 0;
        for (JsonNode node : differences) {
            if (!OPERATION_MISMATCH.equals(node.path("operation").asText())) {
                continue;
            }
            mismatchRows++;
            JsonNode metadata = node.get("metadata");
            if (metadata == null || !metadata.isObject()) {
                continue;
            }
            Set<String> columns = new LinkedHashSet<>();
            collectStrings(metadata.get("changedColumns1"), columns);
            collectStrings(metadata.get("changedColumns2"), columns);
            for (String column : columns) {
                counts.merge(column, 1L, Long::sum);
            }
        }
        if (mismatchRows == 0) {
            return List.of();
        }
        final long mismatchRowCount = mismatchRows;
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(entry -> DiffReportDto.ColumnStat.builder()
                        .name(entry.getKey())
                        .insertCount(0L)
                        .updateCount(entry.getValue())
                        .deleteCount(0L)
                        .differenceCount(entry.getValue())
                        .differencePercentage(DiffReportSupport.round1(
                                entry.getValue() * 100.0 / mismatchRowCount))
                        .aggregatedFromSamples(true)
                        .build())
                .collect(Collectors.toList());
    }

    private List<DiffReportDto.SampleDiff> buildSamples(JsonNode differences) {
        if (differences == null || !differences.isArray()) {
            return List.of();
        }
        List<DiffReportDto.SampleDiff> samples = new ArrayList<>();
        for (JsonNode node : differences) {
            JsonNode metadata = node.get("metadata");
            Set<String> changedColumns = new LinkedHashSet<>();
            if (metadata != null && metadata.isObject()) {
                collectStrings(metadata.get("changedColumns1"), changedColumns);
                collectStrings(metadata.get("changedColumns2"), changedColumns);
            }
            samples.add(DiffReportDto.SampleDiff.builder()
                    .operation(node.path("operation").asText(null))
                    .primaryKey(primaryKeyOf(node.get("primaryKey")))
                    .metadata(toMap(metadata))
                    .changedColumns(new ArrayList<>(changedColumns))
                    .columnNames(textListOf(node.get("columnNames")))
                    .sourceValues(valueListOf(node.get("sourceValues")))
                    .targetValues(valueListOf(node.get("targetValues")))
                    .build());
        }
        return samples;
    }

    private void collectStrings(JsonNode node, Set<String> target) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    target.add(element.asText());
                } else if (element.isNumber() || element.isBoolean()) {
                    target.add(element.asText());
                }
            }
        } else if (node.isTextual()) {
            target.add(node.asText());
        }
    }

    private List<Object> primaryKeyOf(JsonNode primaryKey) {
        if (primaryKey == null || !primaryKey.isArray()) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode element : primaryKey) {
            if (element.isNull() || element.isMissingNode()) {
                values.add(null);
            } else if (element.isNumber()) {
                values.add(element.numberValue());
            } else if (element.isBoolean()) {
                values.add(element.asBoolean());
            } else {
                values.add(element.asText());
            }
        }
        return values;
    }

    private List<String> textListOf(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            values.add(element.isTextual() ? element.asText() : element.asText(null));
        }
        return values;
    }

    private List<Object> valueListOf(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isNull() || element.isMissingNode()) {
                values.add(null);
            } else if (element.isNumber()) {
                values.add(element.numberValue());
            } else if (element.isBoolean()) {
                values.add(element.asBoolean());
            } else {
                values.add(element.asText());
            }
        }
        return values;
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private long longValue(JsonNode node, String field, long fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : fallback;
    }

    private Double doubleValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }

    private Integer integerValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isIntegralNumber() ? value.asInt() : null;
    }

    private Boolean booleanValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }
}
