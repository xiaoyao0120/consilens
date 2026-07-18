package com.consilens.server.application.config;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.ConfigResponse;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class DefaultConfigArtifactService implements ConfigArtifactService {

    private static final Set<String> CONFIG_TYPES = Set.of(ArtifactKind.CONFIG.name(), ArtifactKind.REPAIR_CONFIG.name());

    private final ArtifactService artifactService;

    public DefaultConfigArtifactService(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @Override
    public ConfigResponse getConfig(String configId) {
        if (configId == null || configId.isBlank()) {
            throw new InvalidInputException("configId is required");
        }
        ArtifactRefDto artifact = artifactService.getArtifact(configId);
        if (!CONFIG_TYPES.contains(artifact.getType())) {
            throw new InvalidInputException("configId must reference a CONFIG or REPAIR_CONFIG artifact");
        }
        ArtifactContentDto content = artifactService.getArtifactContent(configId);
        return ConfigResponse.builder()
                .artifact(artifact)
                .content(content)
                .status("READY")
                .build();
    }
}
