package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.api.dto.PageResponse;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.api.dto.TaskSummaryDto;
import com.consilens.server.application.diff.DiffReportService;
import com.consilens.server.application.task.RunTaskCancelService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.task.RunTaskRetryService;
import com.consilens.server.application.task.RunTaskSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskInstanceControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RunTaskQueryService queryService = mock(RunTaskQueryService.class);
        when(queryService.listTasks(anyInt(), anyInt(), any(), any(), any(), any(), any(),
                anyBoolean(), isNull(), any())).thenReturn(
                PageResponse.<TaskSummaryDto>builder()
                        .total(1).page(1).pageSize(20)
                        .items(List.of(TaskSummaryDto.builder()
                                .taskId("inst_1")
                                .serialNo("s-1")
                                .status("SUCCEEDED")
                                .definitionId("1")
                                .definitionName("orders-check")
                                .build()))
                        .build());
        when(queryService.getTask(eq("inst_1"), any())).thenReturn(TaskQueryResponse.builder()
                .taskId("inst_1")
                .serialNo("s-1")
                .status("SUCCEEDED")
                .definitionId("1")
                .definitionName("orders-check")
                .build());
        mockMvc = MockMvcBuilders.standaloneSetup(new TaskInstanceController(
                        queryService,
                        mock(RunTaskRetryService.class),
                        mock(RunTaskCancelService.class),
                        mock(RunTaskSubmissionService.class),
                        mock(DiffReportService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();
    }

    @Test
    void shouldListInstancesWithDefinitionInfo() throws Exception {
        mockMvc.perform(get("/v1/task-instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value("inst_1"))
                .andExpect(jsonPath("$.data.items[0].definitionId").value("1"))
                .andExpect(jsonPath("$.data.items[0].definitionName").value("orders-check"));
    }

    @Test
    void shouldGetInstanceDetailWithDefinitionInfo() throws Exception {
        mockMvc.perform(get("/v1/task-instances/inst_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definitionId").value("1"))
                .andExpect(jsonPath("$.data.definitionName").value("orders-check"));
    }

    @Test
    void shouldSubmitExternalRun() throws Exception {
        mockMvc.perform(post("/v1/task-instances")
                        .contentType("application/json")
                        .content("{\"serialNo\":\"ext-1\",\"configContent\":{\"keys\":[\"id\"]}}"))
                .andExpect(status().isAccepted());
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
