package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.domain.enumtype.ArtifactKind;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PlanTaskHandler implements CapabilityHandler<PlanRequest> {

    private final ArtifactService artifactService;
    private final ServerCompareConfigService configService;

    public PlanTaskHandler(ArtifactService artifactService, ServerCompareConfigService configService) {
        this.artifactService = artifactService;
        this.configService = configService;
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.PLAN;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, PlanRequest request) {
        ServerCompareConfig config = configService.fromPlanRequest(request);
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.CONFIG,
                "json",
                config,
                Map.of("source", "plan"));
        return CapabilityResultSupport.result(artifact);
    }
}
