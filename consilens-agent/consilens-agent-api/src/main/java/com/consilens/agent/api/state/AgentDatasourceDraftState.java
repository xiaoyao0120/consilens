package com.consilens.agent.api.state;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

import java.util.Map;

/**
 * Draft for one side (source or target). Business IDs are strings; only the
 * adapter boundary converts to Long for existing services. Secrets never live
 * in {@code nonSecretParams}.
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class AgentDatasourceDraftState {
    String draftId;
    String datasourceId;
    String name;
    String type;
    Map<String, AgentSlotValue> nonSecretParams;
    AgentSecretStatus secretStatus;
    String secretRequestId;
    AgentProbeStatus probeStatus;
    String paramDigest;
    String database;
    String table;
    String columnsRef;
}
