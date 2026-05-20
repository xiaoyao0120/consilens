package com.consilens.server.infrastructure.db.repository;

import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.db.entity.ArtifactEntity;
import com.consilens.server.infrastructure.db.service.ArtifactPersistenceService;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class MybatisPlusArtifactRepository implements ArtifactRepository {

    private final ArtifactPersistenceService artifactPersistenceService;

    public MybatisPlusArtifactRepository(ArtifactPersistenceService artifactPersistenceService) {
        this.artifactPersistenceService = artifactPersistenceService;
    }

    @Override
    public Optional<ArtifactRecord> findById(String artifactId) {
        return Optional.ofNullable(artifactPersistenceService.getById(artifactId))
                .map(this::toRecord);
    }

    @Override
    public List<ArtifactRecord> listByTaskId(Long taskId) {
        return artifactPersistenceService.listByTaskId(taskId).stream()
                .map(this::toRecord)
                .collect(Collectors.toList());
    }

    @Override
    public ArtifactRecord save(ArtifactRecord artifactRecord) {
        ArtifactEntity entity = toEntity(artifactRecord);
        artifactPersistenceService.save(entity);
        return toRecord(entity);
    }

    private ArtifactEntity toEntity(ArtifactRecord record) {
        ArtifactEntity entity = new ArtifactEntity();
        entity.setId(record.getId());
        entity.setTaskId(record.getTaskId());
        entity.setTraceId(record.getTraceId());
        entity.setArtifactType(record.getArtifactType());
        entity.setArtifactFormat(record.getArtifactFormat());
        entity.setStorageType(record.getStorageType());
        entity.setStorageUri(record.getStorageUri());
        entity.setSha256(record.getSha256());
        entity.setMetadataJson(record.getMetadataJson());
        entity.setCreatedAt(DbTimeSupport.toLocalDateTime(record.getCreatedAt()));
        return entity;
    }

    private ArtifactRecord toRecord(ArtifactEntity entity) {
        if (entity == null) {
            return null;
        }
        return ArtifactRecord.builder()
                .id(entity.getId())
                .taskId(entity.getTaskId())
                .traceId(entity.getTraceId())
                .artifactType(entity.getArtifactType())
                .artifactFormat(entity.getArtifactFormat())
                .storageType(entity.getStorageType())
                .storageUri(entity.getStorageUri())
                .sha256(entity.getSha256())
                .metadataJson(entity.getMetadataJson())
                .createdAt(DbTimeSupport.toInstant(entity.getCreatedAt()))
                .build();
    }
}
