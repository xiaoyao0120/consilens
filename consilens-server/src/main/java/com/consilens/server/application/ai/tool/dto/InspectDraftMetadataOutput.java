package com.consilens.server.application.ai.tool.dto;

import com.consilens.server.api.dto.MetadataColumnDto;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class InspectDraftMetadataOutput {
    String draftId;
    String operation;
    String database;
    String table;
    List<String> databases;
    List<String> tables;
    List<MetadataColumnDto> columns;
    boolean truncated;
}
