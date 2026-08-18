package com.consilens.server.infrastructure.ai;

import com.consilens.server.application.ai.AgentRuntimeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the default {@code ai.enabled=false} no worker, scheduler or recovery
 * component is created and the non-AI API surface is untouched.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "spring.sql.init.mode=always"
})
class AgentAiConfigurationDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void disabledProfileCreatesNoAgentRuntimeBeans() {
        assertTrue(context.getBeansOfType(ServerAgentRunScheduler.class).isEmpty());
        assertTrue(context.getBeansOfType(AgentRunRecoveryScheduler.class).isEmpty());
        assertTrue(context.getBeansOfType(ServerAgentRunWorker.class).isEmpty());
        assertFalse(context.getBean(AgentRuntimeFactory.class).isEnabled());
        assertEquals(0, context.getBeansOfType(com.consilens.agent.core.tool.AgentToolRegistry.class).size());
    }
}
