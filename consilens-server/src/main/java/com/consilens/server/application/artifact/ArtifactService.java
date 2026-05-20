package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.domain.enumtype.ArtifactKind;
import com.consilens.server.domain.model.TaskExecutionContext;

import java.util.Map;

public interface ArtifactService {

    ArtifactRefDto getArtifact(String artifactId);

    ArtifactContentDto getArtifactContent(String artifactId);

    ArtifactRefDto writeArtifact(TaskExecutionContext context,
                                 ArtifactKind artifactKind,
                                 String format,
                                 Object content,
                                 Map<String, Object> metadata);
}
