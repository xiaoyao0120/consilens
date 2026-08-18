package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DatasourceRef {
    String id;
    String name;
    String type;
    /** 连接参数里的默认数据库（可能为空）。 */
    String database;
}
