package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.PlannerResult;
import com.consilens.ai.conversation.engine.model.PlannerRoute;
import com.consilens.ai.conversation.engine.model.PlannerType;
import com.consilens.ai.conversation.engine.model.TurnDecision;
import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerBridgeTest {

    private final PlannerBridge bridge = new PlannerBridge();
    private final PlanContextAssembler assembler = new PlanContextAssembler();

    @Test
    void shouldConvertQuestionResultToQuestionDecision() {
        PlannerResult result = PlannerResult.builder()
                .type(PlannerType.QUESTION)
                .route(PlannerRoute.PLAN_CONFIG)
                .question("Need keys")
                .missingSlot("sourceType")
                .missingSlot("keys")
                .build();

        TurnDecision decision = bridge.toTurnDecision(result, context("compare users"), assembler);

        assertEquals(TurnDecision.Type.QUESTION, decision.getType());
        assertTrue(decision.getQuestion().getQuestion().contains("sourceType=mysql"));
        assertTrue(decision.getQuestion().getQuestion().contains("输入规则"));
        assertTrue(decision.getQuestion().getQuestion().contains("继续追问下一组"));
        assertTrue(decision.getQuestion().getExpectedKeys().contains("sourceType"));
    }

    @Test
    void shouldConvertPlanResultToActionDecision() {
        PlannerResult result = PlannerResult.builder()
                .type(PlannerType.PLAN)
                .route(PlannerRoute.PLAN_CONFIG)
                .normalizedGoal("compare users")
                .slot("sourceType", "mysql")
                .slot("targetType", "postgresql")
                .slot("sourceTable", "users")
                .slot("targetTable", "users")
                .build();

        TurnDecision decision = bridge.toTurnDecision(result, context("compare users"), assembler);

        assertEquals(TurnDecision.Type.ACTION, decision.getType());
        assertEquals("plan", decision.getActionPlan().getCommandName());
    }

    @Test
    void shouldReturnTemplateMessageWhenTemplateRequestIsMisroutedToDiagnose() {
        PlannerResult result = PlannerResult.builder()
                .type(PlannerType.PLAN)
                .route(PlannerRoute.DIAGNOSE)
                .normalizedGoal("template")
                .build();

        TurnDecision decision = bridge.toTurnDecision(result, context("你能不能给一个模版给我"), assembler);

        assertEquals(TurnDecision.Type.MESSAGE, decision.getType());
        assertTrue(decision.getMessage().contains("模板") || decision.getMessage().contains("template"));
        assertTrue(decision.getMessage().contains("source:"));
    }

    private PlannerContext context(String rawInput) {
        return PlannerContext.builder()
                .session(AiSession.builder()
                        .sessionId("s-1")
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .status("ready")
                        .currentTask("doctor")
                        .build())
                .rawInput(rawInput)
                .build();
    }
}
