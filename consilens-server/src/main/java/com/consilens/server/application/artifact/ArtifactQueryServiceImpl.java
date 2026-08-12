package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.ArtifactPage;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ArtifactQueryServiceImpl implements ArtifactQueryService {

    /**
     * 列表场景下 RUN_RESULT content 超过该大小仅回传 size，不解析 differenceCount。
     */
    private static final long MAX_SUMMARY_CONTENT_BYTES = 2L * 1024 * 1024;

    private final ArtifactRepository artifactRepository;
    private final ArtifactContentStore artifactContentStore;
    private final ObjectMapper objectMapper;

    public ArtifactQueryServiceImpl(ArtifactRepository artifactRepository,
                                       ArtifactContentStore artifactContentStore,
                                       ObjectMapper objectMapper) {
        this.artifactRepository = artifactRepository;
        this.artifactContentStore = artifactContentStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResponse<ArtifactListDto> listArtifacts(int page,
                                                       int pageSize,
                                                       Collection<ArtifactKind> kinds,
                                                       String keyword,
                                                       Instant startTime,
                                                       Instant endTime,
                                                       String traceId) {
        ArtifactPage artifactPage = artifactRepository.listArtifactPage(page,
                pageSize,
                kinds,
                keyword,
                startTime,
                endTime);
        List<ArtifactListDto> items = artifactPage.getItems().stream()
                .map(this::toListDto)
                .collect(Collectors.toList());
        return PageResponse.<ArtifactListDto>builder()
                .total(artifactPage.getTotal())
                .page(page)
                .pageSize(pageSize)
                .items(items)
                .build();
    }

    private ArtifactListDto toListDto(ArtifactRecord record) {
        if (record.getArtifactType() == ArtifactKind.RUN_RESULT) {
            return toRunResultListDto(record);
        }
        return ArtifactListDto.builder()
                .artifactId(record.getId())
                .artifactType(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .sizeBytes(sizeOf(record))
                .createdAt(record.getCreatedAt())
                .metadata(readMetadata(record.getMetadataJson()))
                .build();
    }

    /**
     * For RUN_RESULT artifacts, read the content once and derive both the byte size
     * and the total difference count (statistics.totalDifferences) to avoid double IO.
     */
    private ArtifactListDto toRunResultListDto(ArtifactRecord record) {
        RunResultMeta meta = readRunResultMeta(record);
        return ArtifactListDto.builder()
                .artifactId(record.getId())
                .artifactType(record.getArtifactType().name())
                .format(record.getArtifactFormat())
                .sizeBytes(meta != null ? meta.sizeBytes : sizeOf(record))
                .createdAt(record.getCreatedAt())
                .metadata(readMetadata(record.getMetadataJson()))
                .differenceCount(meta != null ? meta.differenceCount : null)
                .build();
    }

    private RunResultMeta readRunResultMeta(ArtifactRecord record) {
        try {
            if (record.getStorageUri() == null || record.getStorageUri().isBlank()) {
                return null;
            }
            long sizeBytes = artifactContentStore.size(record.getStorageUri());
            // 列表场景避免全量读取超大 content：超过阈值仅回传大小（differenceCount 可空降级）
            if (sizeBytes > MAX_SUMMARY_CONTENT_BYTES) {
                return new RunResultMeta(sizeBytes, null);
            }
            byte[] bytes = artifactContentStore.read(record.getStorageUri());
            JsonNode root = objectMapper.readTree(bytes);
            if (root == null) {
                return new RunResultMeta(sizeBytes, null);
            }
            if (root.path("success").isBoolean() && !root.path("success").asBoolean()) {
                return new RunResultMeta(sizeBytes, null);
            }
            JsonNode statistics = root.get("statistics");
            Long differenceCount = null;
            if (statistics != null && statistics.isObject()
                    && statistics.path("totalDifferences").isNumber()) {
                differenceCount = statistics.path("totalDifferences").asLong();
            } else if (root.path("differenceCount").isNumber()) {
                differenceCount = root.path("differenceCount").asLong();
            }
            return new RunResultMeta(sizeBytes, differenceCount);
        } catch (Exception exception) {
            return null;
        }
    }

    private static final class RunResultMeta {
        private final long sizeBytes;
        private final Long differenceCount;

        private RunResultMeta(long sizeBytes, Long differenceCount) {
            this.sizeBytes = sizeBytes;
            this.differenceCount = differenceCount;
        }
    }

    private Long sizeOf(ArtifactRecord record) {
        try {
            if (record.getStorageUri() == null || record.getStorageUri().isBlank()) {
                return null;
            }
            return artifactContentStore.size(record.getStorageUri());
        } catch (Exception exception) {
            return null;
        }
    }

    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }
}
