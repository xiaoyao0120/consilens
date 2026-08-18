package com.consilens.server.api.controller.ai;

import com.consilens.agent.api.store.AgentSecretStore;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.server.infrastructure.ai.MyBatisAgentPersistence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "consilens.server.ai.enabled=true",
        "consilens.server.ai.fail-fast=false",
        "consilens.server.ai.api-key-env=CONSILENS_TEST_FAKE_KEY",
        "consilens.server.ai.secret-key-env=CONSILENS_TEST_FAKE_SECRET",
        "consilens.server.ai.secret-store=memory",
        "consilens.server.ai.poller-enabled=false",
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "spring.sql.init.mode=always"
})
class AgentSecretControllerTest {

    private static final String SECRET_KEY_ENV = "CONSILENS_TEST_FAKE_SECRET";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentSecretStore secretStore;

    @Autowired
    private MyBatisAgentPersistence persistence;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void setUpKey() {
        System.setProperty(SECRET_KEY_ENV,
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void clearKey() {
        System.clearProperty(SECRET_KEY_ENV);
    }

    @Test
    void secretSubmissionIsAcceptedWithoutEchoingValues() throws Exception {
        persistence.createSession(AgentSessionRecord.builder()
                .id("session-1").actorId("local").requestId("create-s")
                .status(AgentSessionStatus.WAITING_SECRET)
                .workflowStage(AgentWorkflowStage.COLLECTING_DATASOURCES)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(java.time.Instant.now()).updatedAt(java.time.Instant.now())
                .build());
        String requestId = secretStore.createRequest("local", "session-1", "draft_source",
                Instant.now().plusSeconds(600), 8);
        String body = "{\"secretRequestId\":\"" + requestId + "\","
                + "\"values\":{\"password\":\"hunter2\"}}";

        String responseBody = mockMvc.perform(post("/v1/ai/sessions/session-1/secrets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        // The response must not contain the submitted value.
        assertFalse(responseBody.contains("hunter2"));
        // Double submission is rejected (CAS once).
        mockMvc.perform(post("/v1/ai/sessions/session-1/secrets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SECRET_REQUEST_STALE"));
    }

    @Test
    void staleRequestIdConflicts() throws Exception {
        String body = "{\"secretRequestId\":\"sec_missing\",\"values\":{\"password\":\"x\"}}";
        mockMvc.perform(post("/v1/ai/sessions/session-1/secrets")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SECRET_REQUEST_STALE"));
    }
}
