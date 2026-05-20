package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.RepairRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.enumtype.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepairTaskHandlerTest {

    @Test
    void shouldRejectNonNumericDifferenceCount() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifactContent("diagnosis-artifact")).thenReturn(ArtifactContentDto.builder()
                .artifactId("diagnosis-artifact")
                .artifactType(ArtifactKind.DIAGNOSIS.name())
                .artifactFormat("json")
                .content("{\"differenceCount\":\"bad\"}")
                .build());
        RepairTaskHandler handler = new RepairTaskHandler(artifactService, new ObjectMapper());
        RepairRequest request = new RepairRequest();
        request.setDiagnosisArtifactId("diagnosis-artifact");

        assertThrows(InvalidInputException.class,
                () -> handler.handle(TaskExecutionContext.builder().traceId("trace-test").build(), request));
    }
}
