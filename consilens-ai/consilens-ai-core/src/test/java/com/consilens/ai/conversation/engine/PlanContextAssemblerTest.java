package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.PlannerResult;
import com.consilens.ai.conversation.engine.model.PlannerRoute;
import com.consilens.ai.conversation.engine.model.PlannerType;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanContextAssemblerTest {

    private final PlanContextAssembler assembler = new PlanContextAssembler();

    @Test
    void shouldBuildConfigRequestHintsFromStructuredSlots() {
        AiSession session = baseSession("s-1");
        PlannerResult result = PlannerResult.builder()
                .type(PlannerType.PLAN)
                .route(PlannerRoute.PLAN_CONFIG)
                .normalizedGoal("compare mysql users and postgresql users")
                .slot("sourceType", "mysql")
                .slot("targetType", "postgresql")
                .slot("sourceTable", "users")
                .slot("targetTable", "users")
                .slot("keys", java.util.List.of("id"))
                .assumption("strategyMode=checksum")
                .build();

        ConfigGenerationRequest request = assembler.toConfigRequest(
                result,
                PlannerContext.builder().session(session).rawInput("我想比较 mysql 和 postgresql 中 users 表的数据").build(),
                session);

        assertTrue(request.getHints().contains("sourceType=mysql"));
        assertTrue(request.getHints().contains("targetType=postgresql"));
        assertTrue(request.getHints().contains("sourceTable=users"));
        assertTrue(request.getHints().contains("targetTable=users"));
        assertTrue(request.getHints().contains("keys=id"));
    }

    @Test
    void shouldMapPlannerRouteToRunActionPlan() {
        AiSession session = baseSession("s-2");
        PlannerResult result = PlannerResult.builder()
                .type(PlannerType.PLAN)
                .route(PlannerRoute.RUN_DIFF)
                .normalizedGoal("run latest compare")
                .build();

        assertEquals("run", assembler.toActionPlan(
                result,
                PlannerContext.builder().session(session).rawInput("run").build(),
                session).getCommandName());
    }

    private AiSession baseSession(String sessionId) {
        return AiSession.builder()
                .sessionId(sessionId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .build();
    }
}
