package com.consilens.server.domain.model;

import com.consilens.server.domain.enumtype.ArtifactKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactRecord {

    private String id;
    private Long taskId;
    private String traceId;
    private ArtifactKind artifactType;
    private String artifactFormat;
    private String storageType;
    private String storageUri;
    private String sha256;
    private String metadataJson;
    private Instant createdAt;
}
