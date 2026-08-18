package com.consilens.server.application.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.api.store.AgentSecretEnvelope;
import com.consilens.agent.api.store.AgentSecretStore;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.server.infrastructure.ai.AgentSecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Out-of-band secret submission: encrypts the values map with AAD bound to
 * actor/session/request, CAS-fulfills the request once, records a model-visible
 * SECRET_PROVIDED event (no values) and enqueues a resume run.
 */
public class AgentSecretApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AgentSecretApplicationService.class);

    private final AgentSecretStore secretStore;
    private final AgentSecretCipher cipher;
    private final AgentPersistence persistence;
    private final AgentRunEnqueueService runEnqueueService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentSecretApplicationService(AgentSecretStore secretStore,
                                         AgentSecretCipher cipher,
                                         AgentPersistence persistence,
                                         AgentRunEnqueueService runEnqueueService) {
        this.secretStore = secretStore;
        this.cipher = cipher;
        this.persistence = persistence;
        this.runEnqueueService = runEnqueueService;
    }

    public boolean submit(String sessionId, String actorId, String secretRequestId,
                          Map<String, String> values) {
        AgentSessionRecord session = persistence.findSession(sessionId).orElse(null);
        if (session == null || session.getStatus() != AgentSessionStatus.WAITING_SECRET) {
            log.warn("agent secret submit rejected: sessionId={} secretRequestId={} sessionStatus={}",
                    sessionId, secretRequestId, session == null ? "null" : session.getStatus());
            return false;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException e) {
            return false;
        }
        AgentSecretEnvelope envelope = cipher.encrypt(payload.toCharArray(),
                AgentSecretCipher.aad(actorId, sessionId, secretRequestId));
        boolean fulfilled = secretStore.fulfill(secretRequestId, actorId, sessionId,
                envelope);
        if (!fulfilled) {
            log.warn("agent secret fulfill failed: sessionId={} secretRequestId={}",
                    sessionId, secretRequestId);
            return false;
        }
        log.info("agent secret submitted: sessionId={} secretRequestId={} fields={}",
                sessionId, secretRequestId, values == null ? 0 : values.keySet());
        long nextSeq = persistence.nextSeq(sessionId);
        AgentEvent provided = AgentEventFactory.create(
                AgentEventType.SECRET_PROVIDED, AgentEventVisibility.MODEL_VISIBLE,
                sessionId, null, null,
                AgentEventFactory.payload()
                        .put("secretRequestId", secretRequestId)
                        .put("status", "PROVIDED"));
        java.util.List<AgentEvent> appended;
        try {
            appended = persistence.appendEvents(sessionId, nextSeq, List.of(provided));
        } catch (RuntimeException e) {
            secretStore.revoke(secretRequestId, actorId, sessionId);
            throw e;
        }
        if (appended == null) {
            // 事件追加冲突：撤销凭据，避免“已 PROVIDED 但永不恢复”的悬挂状态。
            secretStore.revoke(secretRequestId, actorId, sessionId);
            return false;
        }
        runEnqueueService.enqueueResume(sessionId, "sec_" + secretRequestId, secretRequestId);
        return true;
    }
}
