package com.consilens.ai.conversation.engine;

import com.consilens.ai.session.model.PendingQuestionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultClarificationManagerTest {

    private final DefaultClarificationManager manager = new DefaultClarificationManager();

    @Test
    void shouldPreserveSingleKeyLegacyMergeForPlainAnswer() {
        PendingQuestionState pending = PendingQuestionState.builder()
                .question("Need keys")
                .originalRequest("compare users")
                .expectedKey("keys")
                .blocking(true)
                .createdAt(Instant.now())
                .build();

        String merged = manager.merge(pending, "order_id");

        assertTrue(merged.contains("keys: order_id"));
    }

    @Test
    void shouldNormalizeMultiLineFormInputToCanonicalKeyValueLines() {
        PendingQuestionState pending = PendingQuestionState.builder()
                .question("Need source and target")
                .originalRequest("我要比对明细和汇总")
                .expectedKey("sourceType")
                .expectedKey("targetType")
                .expectedKey("keys")
                .blocking(true)
                .createdAt(Instant.now())
                .build();

        String merged = manager.merge(pending,
                "sourceType=mysql\n" +
                        "targetType=postgresql\n" +
                        "keys=order_id,user_id");

        assertTrue(merged.contains("sourceType=mysql"));
        assertTrue(merged.contains("targetType=postgresql"));
        assertTrue(merged.contains("keys=order_id,user_id"));
    }

    @Test
    void shouldExtractKeyFieldsFromYamlSnippet() {
        PendingQuestionState pending = PendingQuestionState.builder()
                .question("Need missing slots")
                .originalRequest("compare data")
                .expectedKey("sourceType")
                .expectedKey("targetType")
                .expectedKey("sourceTable")
                .expectedKey("targetTable")
                .expectedKey("keys")
                .blocking(true)
                .createdAt(Instant.now())
                .build();

        String merged = manager.merge(pending, ""
                + "source:\n"
                + "  type: mysql\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: order_detail\n"
                + "target:\n"
                + "  type: postgresql\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: order_summary\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source:\n"
                + "      - order_id\n"
                + "    target:\n"
                + "      - order_id\n");

        assertTrue(merged.contains("sourceType=mysql"));
        assertTrue(merged.contains("targetType=postgresql"));
        assertTrue(merged.contains("sourceTable=order_detail"));
        assertTrue(merged.contains("targetTable=order_summary"));
        assertTrue(merged.contains("keys=order_id"));
    }
}
