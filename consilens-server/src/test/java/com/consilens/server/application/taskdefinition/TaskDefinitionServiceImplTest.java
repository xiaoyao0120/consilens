package com.consilens.server.application.taskdefinition;

import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionCreateRequest;
import com.consilens.server.api.dto.TaskDefinitionDetailDto;
import com.consilens.server.api.dto.TaskDefinitionDto;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskDefinitionRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.support.crypto.CryptoSupport;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultTaskDefinitionServiceTest {

    private static final Long DEF_ID = 1L;

    private TaskDefinitionRepository definitionRepository;
    private TaskRepository taskRepository;
    private DataSourceRepository dataSourceRepository;
    private RunTaskSubmissionService submissionService;
    private TaskDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        definitionRepository = mock(TaskDefinitionRepository.class);
        taskRepository = mock(TaskRepository.class);
        dataSourceRepository = mock(DataSourceRepository.class);
        submissionService = mock(RunTaskSubmissionService.class);
        service = new TaskDefinitionServiceImpl(definitionRepository, taskRepository,
                submissionService,
                new ServerCompareConfigService(mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new ObjectMapper()),
                new ObjectMapper());
    }

    @Test
    void shouldCreateDefinition() {
        when(definitionRepository.save(any())).thenAnswer(invocation -> {
            TaskDefinitionRecord record = invocation.getArgument(0);
            record.setId(DEF_ID);
            record.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            record.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
            return record;
        });

        TaskDefinitionDto dto = service.create(validRequest());

        assertEquals("orders-check", dto.getName());
        assertEquals(true, dto.getEnabled());
        ArgumentCaptor<TaskDefinitionRecord> captor = ArgumentCaptor.forClass(TaskDefinitionRecord.class);
        verify(definitionRepository).save(captor.capture());
        assertTrue(captor.getValue().getDefinitionKey().startsWith("taskdef_"));
        assertTrue(captor.getValue().getConfig().contains("\"keys\""));
    }

    @Test
    void shouldRejectDuplicateName() {
        when(definitionRepository.findByName("orders-check"))
                .thenReturn(Optional.of(TaskDefinitionRecord.builder().id(9L).build()));

        assertThrows(IllegalArgumentException.class, () -> service.create(validRequest()));
    }

    @Test
    void shouldRejectInlineConnectionPassword() {
        TaskDefinitionCreateRequest request = validRequest();
        request.setConfig(Map.of(
                "source", Map.of("type", "mysql", "table", "orders",
                        "connection", Map.of("url", "jdbc:mysql://x", "password", "secret")),
                "target", Map.of("type", "mysql", "table", "orders"),
                "keys", List.of("id")));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldRejectInvalidConfig() {
        TaskDefinitionCreateRequest request = validRequest();
        request.setConfig(Map.of("source", Map.of("type", "mysql")));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void shouldGetDetailWithRecentInstances() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record()));
        when(taskRepository.listByDefinitionId(DEF_ID, 5)).thenReturn(List.of(
                TaskInstanceRecord.builder().instanceKey("inst_1").serialNo("s-1")
                        .status(TaskStatus.SUCCEEDED)
                        .submitTime(Instant.parse("2026-01-01T00:00:00Z")).build()));

        TaskDefinitionDetailDto detail = service.get(DEF_ID);

        assertEquals("orders-check", detail.getName());
        assertEquals(1, detail.getRecentInstances().size());
        assertEquals("inst_1", detail.getRecentInstances().get(0).getInstanceId());
        assertEquals("SUCCEEDED", detail.getRecentInstances().get(0).getStatus());
    }

    @Test
    void shouldRunDefinitionAndUpdateLastRunAt() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record()));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        TaskAcceptedResponse accepted = service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        assertEquals("inst_1", accepted.getTaskId());
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), eq("trace-x"));
        assertNotNull(captor.getValue().getConfigContent());
        verify(definitionRepository).updateLastRunAt(eq(DEF_ID), any());
    }

    @Test
    void shouldRejectRunWhenDisabled() {
        TaskDefinitionRecord disabled = record();
        disabled.setEnabled(false);
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(disabled));

        assertThrows(IllegalArgumentException.class,
                () -> service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x"));
        verify(submissionService, never()).submit(any(), any());
    }

    @Test
    void shouldThrowNotFoundForMissingDefinition() {
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.get(DEF_ID));
        assertThrows(ResourceNotFoundException.class, () -> service.run(DEF_ID, null, "trace"));
    }

    @Test
    void shouldPassThroughDatasourceIdAndSetDefinitionIdOnRun() {
        TaskDefinitionRecord recordWithDs = TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .definitionKey("taskdef_1")
                .name("orders-check")
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\",\"datasourceId\":5},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\",\"datasourceId\":5},"
                        + "\"keys\":[\"id\"]}")
                .enabled(true)
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(recordWithDs));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), eq("trace-x"));
        RunRequest runRequest = captor.getValue();
        assertEquals(DEF_ID, runRequest.getDefinitionId());
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) runRequest.getConfigContent();
        Map<String, Object> source = (Map<String, Object>) config.get("source");
        assertEquals(5, source.get("datasourceId"));
        // 连接注入发生在执行端（RunTaskHandler），提交链路只透传 datasourceId
        assertTrue(source.get("connection") == null
                || ((Map<?, ?>) source.get("connection")).isEmpty());
    }

    @Test
    void shouldTruncateGeneratedSerialNoForLongNames() {
        TaskDefinitionRecord record = TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .name(java.util.UUID.randomUUID().toString().repeat(3))
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}")
                .enabled(true)
                .build();
        when(definitionRepository.findById(DEF_ID)).thenReturn(Optional.of(record));
        when(submissionService.submit(any(), any())).thenReturn(
                TaskAcceptedResponse.builder().taskId("inst_1").status("PENDING").build());

        service.run(DEF_ID, new TaskDefinitionRunRequest(), "trace-x");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(submissionService).submit(captor.capture(), any());
        assertTrue(captor.getValue().getSerialNo().length() <= 128);
    }

    private TaskDefinitionCreateRequest validRequest() {
        TaskDefinitionCreateRequest request = new TaskDefinitionCreateRequest();
        request.setName("orders-check");
        request.setDescription("check orders");
        request.setConfig(Map.of(
                "source", Map.of("type", "mysql", "table", "orders"),
                "target", Map.of("type", "mysql", "table", "orders"),
                "keys", List.of("id")));
        return request;
    }

    private TaskDefinitionRecord record() {
        return TaskDefinitionRecord.builder()
                .id(DEF_ID)
                .definitionKey("taskdef_1")
                .name("orders-check")
                .taskType("RUN")
                .config("{\"source\":{\"type\":\"mysql\",\"table\":\"orders\"},"
                        + "\"target\":{\"type\":\"mysql\",\"table\":\"orders\"},\"keys\":[\"id\"]}")
                .enabled(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
