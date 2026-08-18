package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TableCandidate {
    String datasourceId;
    String datasourceName;
    String database;
    String table;
}
