package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.ArtifactRecord;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository {

    Optional<ArtifactRecord> findById(String artifactId);

    List<ArtifactRecord> listByTaskId(Long taskId);

    ArtifactRecord save(ArtifactRecord artifactRecord);
}
