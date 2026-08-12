package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.ArtifactListDto;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.ArtifactPage;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultArtifactQueryServiceTest {

    private ArtifactRepository artifactRepository;
    private ArtifactContentStore contentStore;
    private ArtifactQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        artifactRepository = mock(ArtifactRepository.class);
        contentStore = mock(ArtifactContentStore.class);
        service = new ArtifactQueryServiceImpl(artifactRepository, contentStore, new ObjectMapper());
    }

    @Test
    void shouldExposeDifferenceCountForRunResultArtifacts() throws Exception {
        ArtifactRecord runResult = ArtifactRecord.builder()
                .id("result-1")
                .taskId(1L)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("storage://result-1")
                .metadataJson("{\"source\": \"pg\"}")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(artifactRepository.listArtifactPage(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new ArtifactPage(1, List.of(runResult)));
        byte[] content = ("{\"success\": true, \"differenceCount\": 2, \"statistics\": {"
                + "\"sourceRowCount\": 100, \"targetRowCount\": 100, \"totalDifferences\": 2, "
                + "\"differencePercentage\": 0.02}}")
                .getBytes(StandardCharsets.UTF_8);
        when(contentStore.size("storage://result-1")).thenReturn((long) content.length);
        when(contentStore.read("storage://result-1")).thenReturn(content);

        PageResponse<ArtifactListDto> result = service.listArtifacts(1, 20, List.of(ArtifactKind.RUN_RESULT),
                null, null, null, "trace");

        ArtifactListDto dto = result.getItems().get(0);
        assertEquals(2L, dto.getDifferenceCount());
        assertEquals((long) content.length, dto.getSizeBytes());
        assertEquals("RUN_RESULT", dto.getArtifactType());
        assertEquals("pg", dto.getMetadata().get("source"));
        verify(contentStore).read("storage://result-1");
    }

    @Test
    void shouldUseSizeApiForNonRunResultArtifacts() {
        ArtifactRecord config = ArtifactRecord.builder()
                .id("config-1")
                .taskId(1L)
                .artifactType(ArtifactKind.CONFIG)
                .artifactFormat("json")
                .storageUri("storage://config-1")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(artifactRepository.listArtifactPage(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new ArtifactPage(1, List.of(config)));
        when(contentStore.size("storage://config-1")).thenReturn(123L);

        PageResponse<ArtifactListDto> result = service.listArtifacts(1, 20, List.of(ArtifactKind.CONFIG),
                null, null, null, "trace");

        ArtifactListDto dto = result.getItems().get(0);
        assertEquals(123L, dto.getSizeBytes());
        assertNull(dto.getDifferenceCount());
        verify(contentStore, never()).read(anyString());
    }

    @Test
    void shouldReturnNullDifferenceCountWhenContentUnreadable() throws Exception {
        ArtifactRecord runResult = ArtifactRecord.builder()
                .id("result-broken")
                .taskId(1L)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("storage://broken")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(artifactRepository.listArtifactPage(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new ArtifactPage(1, List.of(runResult)));
        when(contentStore.read("storage://broken"))
                .thenThrow(new IllegalStateException("storage unavailable"));

        PageResponse<ArtifactListDto> result = service.listArtifacts(1, 20, List.of(ArtifactKind.RUN_RESULT),
                null, null, null, "trace");

        ArtifactListDto dto = result.getItems().get(0);
        assertNull(dto.getDifferenceCount());
        assertEquals("RUN_RESULT", dto.getArtifactType());
    }

    @Test
    void shouldReturnNullDifferenceCountForFailedRunResult() throws Exception {
        ArtifactRecord runResult = ArtifactRecord.builder()
                .id("result-failed")
                .taskId(1L)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("storage://failed")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(artifactRepository.listArtifactPage(anyInt(), anyInt(), any(), any(), any(), any()))
                .thenReturn(new ArtifactPage(1, List.of(runResult)));
        byte[] content = "{\"success\": false, \"errorCode\": \"DB_TIMEOUT\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(contentStore.size("storage://failed")).thenReturn((long) content.length);
        when(contentStore.read("storage://failed")).thenReturn(content);

        PageResponse<ArtifactListDto> result = service.listArtifacts(1, 20, List.of(ArtifactKind.RUN_RESULT),
                null, null, null, "trace");

        ArtifactListDto dto = result.getItems().get(0);
        assertNull(dto.getDifferenceCount());
        assertEquals((long) content.length, dto.getSizeBytes());
    }
}
