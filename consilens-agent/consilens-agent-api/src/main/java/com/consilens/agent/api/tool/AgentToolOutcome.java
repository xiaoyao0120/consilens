package com.consilens.agent.api.tool;

import com.consilens.agent.api.store.AgentResourceRef;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;
import java.time.Instant;

/**
 * Structured tool result. State changes are never encoded here; they belong to
 * the tool's typed {@code reduce} method.
 */
@Value
@Builder
public class AgentToolOutcome<O> {
    boolean success;
    String content;
    O structuredData;
    String errorCode;
    boolean retryable;
    ToolResultVisibility visibility;
    List<AgentResourceRef> resourceRefs;
    Map<String, Object> metrics;
    boolean secretInputRequired;
    String secretRequestId;
    String draftId;
    Instant secretExpiresAt;
    boolean approvalRequired;
    String approvalSummary;
    String approvalId;
    String actionDigest;
    boolean questionRequired;
    String question;
    List<String> missingSlots;

    public static <O> AgentToolOutcome<O> ok(O data, String content) {
        return AgentToolOutcome.<O>builder()
                .success(true)
                .structuredData(data)
                .content(content)
                .visibility(ToolResultVisibility.MODEL_AND_USER)
                .resourceRefs(List.of())
                .metrics(Map.of())
                .build();
    }

    public static <O> AgentToolOutcome<O> error(String errorCode, boolean retryable, String safeMessage) {
        return AgentToolOutcome.<O>builder()
                .success(false)
                .errorCode(errorCode)
                .retryable(retryable)
                .content(safeMessage)
                .visibility(ToolResultVisibility.MODEL_AND_USER)
                .resourceRefs(List.of())
                .metrics(Map.of())
                .build();
    }
}
