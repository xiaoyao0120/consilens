package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class InspectDraftMetadataInput {
    String draftId;
    String operation;
    String database;
    String table;
}
