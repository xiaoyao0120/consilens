package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class GetTableSchemaOutput {
    String datasourceId;
    String database;
    String table;
    List<ColumnInfo> columns;
    List<String> primaryKeys;
}
