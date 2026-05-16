package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.TurnDecision;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.intent.AiIntent;
import com.consilens.ai.runtime.intent.IntentRouter;
import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTurnPlannerTest {

    private final IntentRouter router = (session, userInput) -> AiIntent.PLAN_CONFIG;

    @Test
    void shouldAskClarificationForCheckWithoutConfig() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s1", null))
                .commandName("check")
                .build());

        assertEquals(TurnDecision.Type.QUESTION, decision.getType());
    }

    @Test
    void shouldRunCheckWhenConfigExists() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s2", "config-1"))
                .commandName("check")
                .build());

        assertEquals(TurnDecision.Type.ACTION, decision.getType());
        assertEquals("check", decision.getActionPlan().getCommandName());
    }

    @Test
    void shouldAskOnlyForKeysWhenSourceAndTargetArePresent() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s3", null))
                .rawInput("compare mysql.orders -> postgresql.orders")
                .build());

        assertEquals(TurnDecision.Type.QUESTION, decision.getType());
        assertTrue(decision.getQuestion().getQuestion().contains("已识别到你要比较 mysql.orders 和 postgresql.orders 的数据。"));
        assertTrue(decision.getQuestion().getQuestion().contains("继续生成配置还需要 compare keys"));
    }

    @Test
    void shouldAskForSourceAndTargetBeforeKeysWhenPlanGoalIsTooSparse() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s4", null))
                .commandName("plan")
                .commandArgument("compare orders by id")
                .build());

        assertEquals(TurnDecision.Type.QUESTION, decision.getType());
        assertTrue(decision.getQuestion().getQuestion().contains("继续生成配置前，请先说明 source 与 target"));
    }

    @Test
    void shouldAcceptSameConnectorArrowGoalWhenKeysArePresent() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s5", null))
                .commandName("plan")
                .commandArgument("mysql.orders -> mysql.orders_archive keys: id")
                .build());

        assertEquals(TurnDecision.Type.ACTION, decision.getType());
        assertEquals("plan", decision.getActionPlan().getCommandName());
    }

    @Test
    void shouldAskJoinSpecificKeysQuestion() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s6", null))
                .rawInput("compare mysql.orders join postgresql.orders_archive on order_id")
                .build());

        assertEquals(TurnDecision.Type.QUESTION, decision.getType());
        assertTrue(decision.getQuestion().getQuestion().contains("检测到 join 场景"));
        assertTrue(decision.getQuestion().getQuestion().contains("order_id,user_id"));
    }

    @Test
    void shouldAskSqlSpecificSourceTargetQuestion() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s7", null))
                .rawInput("compare sql: select * from orders keys: id")
                .build());

        assertEquals(TurnDecision.Type.QUESTION, decision.getType());
        assertTrue(decision.getQuestion().getQuestion().contains("检测到 SQL 资源线索"));
        assertTrue(decision.getQuestion().getQuestion().contains("mysql: SELECT ... -> postgresql: SELECT ..."));
    }

    @Test
    void shouldIncludeConfigRequestWhenRoutingNaturalLanguagePlanIntent() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s8", null))
                .rawInput("mysql.users -> postgresql.users keys: id")
                .build());

        assertEquals(TurnDecision.Type.ACTION, decision.getType());
        assertEquals("plan", decision.getActionPlan().getCommandName());
        Object req = decision.getActionPlan().getAttributes().get("configRequest");
        assertNotNull(req, "configRequest attribute must be present in the action plan");
        ConfigGenerationRequest request = (ConfigGenerationRequest) req;
        assertTrue(request.getHints().contains("sourceType=mysql"));
        assertTrue(request.getHints().contains("targetType=postgresql"));
        assertTrue(request.getHints().contains("sourceTable=users"));
        assertTrue(request.getHints().contains("targetTable=users"));
        assertTrue(request.getHints().contains("keys=id"));
    }

    @Test
    void shouldAskForKeysWhenChineseCompareInputHasConnectorsButNoKeys() {
        DefaultTurnPlanner planner = new DefaultTurnPlanner(router);

        TurnDecision decision = planner.plan(PlannerContext.builder()
                .session(baseSession("s9", null))
                .rawInput("我想要比较mysql和postgresql中users表的差异")
                .build());

        assertEquals(TurnDecision.Type.QUESTION, decision.getType(), "Should ask clarification for missing keys");
        assertTrue(decision.getQuestion().getQuestion().contains("已识别到你要比较 mysql.users 和 postgresql.users 的数据。"));
        assertTrue(decision.getQuestion().getQuestion().contains("若两边都用同一个主键，直接回复 `id` 即可"));
    }

    private AiSession baseSession(String sessionId, String configArtifactId) {
        return AiSession.builder()
                .sessionId(sessionId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .currentConfigArtifactId(configArtifactId)
                .build();
    }
}
