package com.consilens.server.api.controller;

import com.consilens.server.api.advice.GlobalExceptionHandler;
import com.consilens.server.application.task.RunTaskSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunControllerTest {

    @Test
    void shouldRejectRunRequestWhenSerialNoExceedsDatabaseLimit() throws Exception {
        RunTaskSubmissionService service = mock(RunTaskSubmissionService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/v1/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-run")
                        .content("{\"serialNo\":\"" + "s".repeat(129)
                                + "\",\"configArtifactId\":\"artifact-config\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(service, never()).submit(any(), any());
    }

    @Test
    void shouldRejectRunRequestWhenTimeoutIsNotPositive() throws Exception {
        RunTaskSubmissionService service = mock(RunTaskSubmissionService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/v1/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-run")
                        .content("{\"serialNo\":\"serial-1\",\"configArtifactId\":\"artifact-config\","
                                + "\"options\":{\"timeoutMs\":0}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(service, never()).submit(any(), any());
    }

    private MockMvc mockMvc(RunTaskSubmissionService service) {
        return MockMvcBuilders.standaloneSetup(new RunController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator())
                .build();
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
