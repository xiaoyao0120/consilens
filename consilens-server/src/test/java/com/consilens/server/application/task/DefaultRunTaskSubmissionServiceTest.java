package com.consilens.server.application.task;

import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.model.TaskRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.support.hash.Sha256Support;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRunTaskSubmissionServiceTest {

    @Test
    void shouldReturnExistingTaskWhenConcurrentInsertHitsSerialNoUniqueKey() throws Exception {
        TaskRepository taskRepository = mock(TaskRepository.class);
        RunTaskCommandEnqueueService commandEnqueueService = mock(RunTaskCommandEnqueueService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-concurrent");
        request.setConfigArtifactId("artifact-config");
        String requestPayload = objectMapper.writeValueAsString(request);
        String requestHash = Sha256Support.hex(requestPayload);
        TaskRecord existing = TaskRecord.builder()
                .id(10L)
                .taskKey("task-existing")
                .serialNo(request.getSerialNo())
                .requestHash(requestHash)
                .status(TaskStatus.PENDING)
                .build();
        when(taskRepository.lockBySerialNo(request.getSerialNo())).thenReturn(Optional.empty());
        when(taskRepository.save(any())).thenThrow(new DuplicateKeyException("serial_no"));
        when(taskRepository.findBySerialNo(request.getSerialNo())).thenReturn(Optional.of(existing));

        DefaultRunTaskSubmissionService service = new DefaultRunTaskSubmissionService(taskRepository,
                commandEnqueueService,
                new ConsilensServerProperties(),
                objectMapper,
                new TransactionTemplate(transactionManager()));

        TaskAcceptedResponse response = service.submit(request, "trace-test");

        assertThat(response.getTaskId()).isEqualTo("task-existing");
        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(commandEnqueueService, never()).enqueue(any(), any());
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return transactionManager;
    }
}
