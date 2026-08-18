package com.consilens.server.api.controller.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.policy.AgentActor;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ai.AgentSessionDto;
import com.consilens.server.api.dto.ai.CreateAgentSessionRequest;
import com.consilens.server.api.dto.ai.CreateAgentSessionResponse;
import com.consilens.server.api.dto.ai.DecideApprovalRequest;
import com.consilens.server.api.dto.ai.SendAgentMessageRequest;
import com.consilens.server.api.dto.ai.SendAgentMessageResponse;
import com.consilens.server.application.ai.AgentConversationApplicationService;
import com.consilens.server.application.ai.AgentPrincipalResolver;
import com.consilens.server.support.trace.TraceIdSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/ai/sessions")
@ConditionalOnProperty(prefix = "consilens.server.ai", name = "enabled", havingValue = "true")
public class AgentConversationController {

    private final AgentConversationApplicationService conversationService;
    private final AgentPrincipalResolver principalResolver;

    public AgentConversationController(AgentConversationApplicationService conversationService,
                                       AgentPrincipalResolver principalResolver) {
        this.conversationService = conversationService;
        this.principalResolver = principalResolver;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateAgentSessionResponse>> createSession(
            @RequestBody CreateAgentSessionRequest body,
            HttpServletRequest request) {
        AgentActor actor = principalResolver.resolve();
        AgentSessionDto session = conversationService.createSession(
                actor.getId(), body.getRequestId(), body.getTitle());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        CreateAgentSessionResponse.builder().session(session).build(),
                        TraceIdSupport.getOrCreateTraceId(request)));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<AgentSessionDto> getSession(@PathVariable String sessionId,
                                                   HttpServletRequest request) {
        AgentSessionDto dto = conversationService.getSession(sessionId,
                principalResolver.resolve().getId());
        return ApiResponse.success(dto, TraceIdSupport.getOrCreateTraceId(request));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Boolean> deleteSession(@PathVariable String sessionId,
                                              HttpServletRequest request) {
        conversationService.deleteSession(sessionId, principalResolver.resolve().getId());
        return ApiResponse.success(true, TraceIdSupport.getOrCreateTraceId(request));
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<ApiResponse<SendAgentMessageResponse>> sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendAgentMessageRequest body,
            HttpServletRequest request) {
        SendAgentMessageResponse response = conversationService.sendMessage(
                sessionId, principalResolver.resolve().getId(), body.getRequestId(), body.getText());
        boolean accepted = "ACCEPTED".equals(response.getStatus());
        return ResponseEntity.status(accepted ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResponse.success(response, TraceIdSupport.getOrCreateTraceId(request)));
    }

    @PostMapping("/{sessionId}/approvals/{approvalId}")
    public ResponseEntity<ApiResponse<SendAgentMessageResponse>> decideApproval(
            @PathVariable String sessionId,
            @PathVariable String approvalId,
            @RequestBody DecideApprovalRequest body,
            HttpServletRequest request) {
        try {
            SendAgentMessageResponse response = conversationService.decideApproval(
                    sessionId, principalResolver.resolve().getId(), approvalId,
                    body.getRequestId(), body.getDecision() == null
                            ? AgentApprovalDecision.APPROVE : body.getDecision(),
                    body.getActionDigest(), body.getVersion());
            return ResponseEntity.accepted()
                    .body(ApiResponse.success(response, TraceIdSupport.getOrCreateTraceId(request)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("approval is stale or expired", e.getMessage(),
                            TraceIdSupport.getOrCreateTraceId(request)));
        }
    }

    @PostMapping("/{sessionId}/cancel")
    public ApiResponse<Boolean> cancel(@PathVariable String sessionId, HttpServletRequest request) {
        conversationService.cancel(sessionId, principalResolver.resolve().getId());
        return ApiResponse.success(true, TraceIdSupport.getOrCreateTraceId(request));
    }

    @PostMapping("/{sessionId}/retry")
    public ApiResponse<Boolean> retry(@PathVariable String sessionId,
                                      @RequestBody(required = false) RetryRequest body,
                                      @RequestParam(value = "requestId", required = false) String requestIdParam,
                                      HttpServletRequest request) {
        String requestId = body != null && body.getRequestId() != null
                ? body.getRequestId() : requestIdParam;
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        conversationService.retry(sessionId, principalResolver.resolve().getId(), requestId);
        return ApiResponse.success(true, TraceIdSupport.getOrCreateTraceId(request));
    }

    @GetMapping("/{sessionId}/events")
    public Map<String, Object> events(@PathVariable String sessionId,
                                      @RequestParam(value = "afterSeq", defaultValue = "-1") long afterSeq) {
        List<AgentEvent> events = conversationService.events(sessionId,
                principalResolver.resolve().getId(), afterSeq);
        long lastSeq = events.isEmpty() ? afterSeq : events.get(events.size() - 1).getSeq();
        return Map.of("events", events, "lastSeq", lastSeq);
    }

    public static class RetryRequest {
        private String requestId;

        public String getRequestId() {
            return requestId;
        }

        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }
    }
}
