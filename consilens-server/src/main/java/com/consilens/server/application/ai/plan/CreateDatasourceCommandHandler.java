package com.consilens.server.application.ai.plan;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentResourceRef;
import com.consilens.agent.api.store.AgentResourceType;
import com.consilens.agent.api.store.AgentSecretStore;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.server.api.dto.DataSourceCreateRequest;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.application.datasource.DataSourceService;
import com.consilens.server.application.ai.tool.SecretPayloadCodec;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Executes one CREATE_DATASOURCE plan action: resolves the draft + secret,
 * calls the existing DataSourceService (the only business write entry) and
 * consumes the secret. Reuses the datasource when the draft already has one.
 */
public class CreateDatasourceCommandHandler {

    private final DataSourceService dataSourceService;
    private final AgentSecretStore secretStore;
    private final AgentPersistence persistence;

    public CreateDatasourceCommandHandler(DataSourceService dataSourceService,
                                          AgentSecretStore secretStore,
                                          AgentPersistence persistence) {
        this.dataSourceService = dataSourceService;
        this.secretStore = secretStore;
        this.persistence = persistence;
    }

    public AgentResourceRef create(String sessionId, String actorId, String draftId) {
        AgentWorkingState state = persistence.latestSnapshot(sessionId)
                .orElseThrow(() -> new IllegalStateException("no snapshot for session " + sessionId))
                .getWorkingState();
        AgentDatasourceDraftState draft = findDraft(state, draftId);
        if (draft == null) {
            throw new IllegalArgumentException("draft not found: " + draftId);
        }
        if (draft.getDatasourceId() != null) {
            return AgentResourceRef.builder()
                    .resourceType(AgentResourceType.DATASOURCE)
                    .resourceId(draft.getDatasourceId())
                    .name(draft.getName())
                    .build();
        }
        char[] secret = secretStore.resolve(draft.getSecretRequestId(), actorId, sessionId,
                "create", Instant.now())
                .orElseThrow(() -> new IllegalStateException("secret not resolvable for draft " + draftId));
        Map<String, Object> param = new LinkedHashMap<>(draft.getNonSecretParams() == null
                ? Map.of() : draft.getNonSecretParams().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().getValue())));
        param.put("password", SecretPayloadCodec.extract(secret, "password"));
        DataSourceDto created = dataSourceService.create(DataSourceCreateRequest.builder()
                .name(draft.getName())
                .type(draft.getType())
                .param(param)
                .build());
        secretStore.consume(draft.getSecretRequestId(), actorId, sessionId);
        return AgentResourceRef.builder()
                .resourceType(AgentResourceType.DATASOURCE)
                .resourceId(created.getId())
                .name(created.getName())
                .build();
    }

    public static AgentDatasourceDraftState findDraft(AgentWorkingState state, String draftId) {
        if (state.getSource() != null && draftId.equals(state.getSource().getDraftId())) {
            return state.getSource();
        }
        if (state.getTarget() != null && draftId.equals(state.getTarget().getDraftId())) {
            return state.getTarget();
        }
        return null;
    }
}
