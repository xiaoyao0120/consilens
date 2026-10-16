package com.consilens.server.infrastructure.ai;

import com.consilens.agent.core.tool.AgentToolRegistry;
import com.consilens.server.application.ai.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With {@code ai.enabled=true} and fail-fast disabled (missing test env keys
 * must not block the context) the full runtime is assembled.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "consilens.server.ai.enabled=true",
        "consilens.server.ai.fail-fast=false",
        "consilens.server.ai.api-key-env=CONSILENS_TEST_FAKE_KEY",
        "consilens.server.ai.secret-key-env=CONSILENS_TEST_FAKE_SECRET",
        "consilens.server.ai.poller-enabled=false",
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "spring.sql.init.mode=always"
})
class AgentAiConfigurationEnabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void enabledProfileAssemblesTheFullRuntime() {
        assertFalse(context.getBeansOfType(ServerAgentRunScheduler.class).isEmpty());
        assertFalse(context.getBeansOfType(AgentRunRecoveryScheduler.class).isEmpty());
        assertFalse(context.getBeansOfType(ServerAgentRunWorker.class).isEmpty());
        AgentRuntime runtime = context.getBean(AgentRuntime.class);
        assertNotNull(runtime.loop());
        assertNotNull(runtime.modelClient());
        AgentToolRegistry registry = runtime.registry();
        // 工具集增删都会让本测试失败：数量与各类工具的代表成员一起钉住，
        // 避免只改数字就把能力悄悄改没了。
        assertEquals(14, registry.all().size());
        // 数据源发现/探查
        assertTrue(registry.find("list_datasource_types").isPresent());
        assertTrue(registry.find("get_datasource_form_schema").isPresent());
        assertTrue(registry.find("find_datasource").isPresent());
        assertTrue(registry.find("list_datasources").isPresent());
        assertTrue(registry.find("search_tables").isPresent());
        assertTrue(registry.find("get_table_schema").isPresent());
        assertTrue(registry.find("stage_datasource_draft").isPresent());
        assertTrue(registry.find("probe_datasource").isPresent());
        assertTrue(registry.find("inspect_draft_metadata").isPresent());
        // 对比定义与落库计划
        assertTrue(registry.find("stage_compare_definition").isPresent());
        assertTrue(registry.find("prepare_provisioning_plan").isPresent());
        assertTrue(registry.find("commit_provisioning_plan").isPresent());
        // 运行与查询
        assertTrue(registry.find("run_task_definition").isPresent());
        assertTrue(registry.find("get_task_status").isPresent());
    }
}
