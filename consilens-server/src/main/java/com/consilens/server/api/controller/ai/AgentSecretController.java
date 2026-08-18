package com.consilens.server.api.controller.ai;

import com.consilens.agent.api.policy.AgentActor;
import com.consilens.server.api.dto.ApiResponse;
import com.consilens.server.api.dto.ai.SubmitSecretRequest;
import com.consilens.server.application.ai.AgentPrincipalResolver;
import com.consilens.server.application.ai.AgentSecretApplicationService;
import com.consilens.server.support.trace.TraceIdSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Secret submission endpoint. Values travel directly from the browser to the
 * secret store; HTTP only answers "accepted". The body must never be echoed.
 */
@RestController
@RequestMapping("/v1/ai/sessions/{sessionId}/secrets")
@ConditionalOnProperty(prefix = "consilens.server.ai", name = "enabled", havingValue = "true")
public class AgentSecretController {

    private final AgentSecretApplicationService secretApplicationService;
    private final AgentPrincipalResolver principalResolver;

    public AgentSecretController(AgentSecretApplicationService secretApplicationService,
                                 AgentPrincipalResolver principalResolver) {
        this.secretApplicationService = secretApplicationService;
        this.principalResolver = principalResolver;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @PathVariable String sessionId,
            @RequestBody SubmitSecretRequest body,
            HttpServletRequest request) {
        AgentActor actor = principalResolver.resolve();
        boolean accepted = secretApplicationService.submit(
                sessionId, actor.getId(), body.getSecretRequestId(), body.getValues());
        String traceId = TraceIdSupport.getOrCreateTraceId(request);
        if (!accepted) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("secret request is stale or already fulfilled",
                            "SECRET_REQUEST_STALE", traceId));
        }
        return ResponseEntity.accepted()
                .body(ApiResponse.success(Map.of("status", "ACCEPTED"), traceId));
    }
}
