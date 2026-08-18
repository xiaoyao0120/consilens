package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ColumnInfo {
    String name;
    String dataType;
    boolean nullable;
    boolean primaryKey;
}
