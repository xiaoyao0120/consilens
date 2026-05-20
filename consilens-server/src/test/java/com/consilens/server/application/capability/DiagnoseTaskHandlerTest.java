package com.consilens.server.application.capability;

import com.consilens.server.api.dto.ArtifactContentDto;
import com.consilens.server.api.dto.DiagnoseRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.enumtype.ArtifactKind;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnoseTaskHandlerTest {

    @Test
    void shouldRejectNonNumericDifferenceCount() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.getArtifactContent("run-artifact")).thenReturn(ArtifactContentDto.builder()
                .artifactId("run-artifact")
                .artifactType(ArtifactKind.RUN_RESULT.name())
                .artifactFormat("json")
                .content("{\"success\":true,\"differenceCount\":\"bad\"}")
                .build());
        DiagnoseTaskHandler handler = new DiagnoseTaskHandler(artifactService, new ObjectMapper());
        DiagnoseRequest request = new DiagnoseRequest();
        request.setRunArtifactId("run-artifact");

        assertThrows(InvalidInputException.class,
                () -> handler.handle(TaskExecutionContext.builder().traceId("trace-test").build(), request));
    }
}
