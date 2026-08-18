package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ListDatasourceTypesOutput {
    List<DatasourceTypeItem> types;
}
