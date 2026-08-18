package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

/**
 * One supported datasource type. driverClass stays audit-side and is never
 * exposed to the model.
 */
@Value
@Builder
public class DatasourceTypeItem {
    String type;
    int defaultPort;
}
