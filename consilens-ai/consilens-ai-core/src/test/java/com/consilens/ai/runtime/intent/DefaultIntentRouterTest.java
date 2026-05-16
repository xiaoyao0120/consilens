package com.consilens.ai.runtime.intent;

import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultIntentRouterTest {

    private final DefaultIntentRouter router = new DefaultIntentRouter();

    @Test
    void shouldPlanCompareRequestWhenSessionHasNoConfigYet() {
        AiSession session = AiSession.builder()
                .sessionId("s-1")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .build();

        assertEquals(AiIntent.PLAN_CONFIG, router.route(session, "compare orders between mysql and postgres"));
    }

    @Test
    void shouldRunCompareRequestWhenSessionAlreadyHasConfig() {
        AiSession session = AiSession.builder()
                .sessionId("s-2")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("planned")
                .currentTask("plan")
                .currentConfigArtifactId("config-1")
                .build();

        assertEquals(AiIntent.RUN_DIFF, router.route(session, "compare orders between mysql and postgres"));
    }

    @Test
    void shouldTreatNormalizationHintsAsConfigPlanningWhenNoConfigExists() {
        AiSession session = AiSession.builder()
                .sessionId("s-3")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .build();

        assertEquals(AiIntent.PLAN_CONFIG, router.route(session, "compare orders with normalization timezone UTC and fields name,email"));
    }

    @Test
    void shouldTreatRepairSqlHintsAsRepairIntent() {
        AiSession session = AiSession.builder()
                .sessionId("s-4")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .currentConfigArtifactId("config-1")
                .build();

        assertEquals(AiIntent.REPAIR_CONFIG, router.route(session, "generate repair sql for the latest diff"));
    }

    @Test
    void shouldTreatFilterAndProjectionHintsAsConfigModificationWhenConfigExists() {
        AiSession session = AiSession.builder()
                .sessionId("s-5")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("planned")
                .currentTask("plan")
                .currentConfigArtifactId("config-1")
                .build();

        assertEquals(AiIntent.MODIFY_CONFIG, router.route(session,
                "update the config to filter deleted rows where deleted_at is null and select order_id,status"));
    }

    @Test
    void shouldTreatConcreteChineseCompareGoalAsPlanConfig() {
        AiSession session = AiSession.builder()
                .sessionId("s-6")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .build();

        assertEquals(AiIntent.PLAN_CONFIG, router.route(session, "我想要比较mysql和postgresql中users表的数据"));
    }

    @Test
    void shouldTreatChineseBiDuiCompareGoalAsPlanConfig() {
        AiSession session = AiSession.builder()
                .sessionId("s-6b")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .build();

        assertEquals(AiIntent.PLAN_CONFIG, router.route(session, "我想比对mysql和postgresql的users表数据"));
    }

    @Test
    void shouldTreatConnectorDifferenceQuestionAsGeneralQa() {
        AiSession session = AiSession.builder()
                .sessionId("s-7")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("doctor")
                .build();

        assertEquals(AiIntent.GENERAL_QA, router.route(session, "mysql 和 postgresql 有什么区别"));
    }
}
