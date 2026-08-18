package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Model-visible form field. {@code sensitive} marks fields whose values must
 * never be placed into tool arguments (password/token/secret).
 */
@Value
@Builder
public class DatasourceFormField {
    String name;
    String title;
    String controlType;
    boolean required;
    Object defaultValue;
    List<Map<String, Object>> options;
    boolean sensitive;
}
