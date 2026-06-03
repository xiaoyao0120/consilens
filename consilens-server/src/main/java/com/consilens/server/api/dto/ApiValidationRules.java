package com.consilens.server.api.dto;

public final class ApiValidationRules {

    public static final int ID_MAX_LENGTH = 128;
    public static final String SAFE_ID_PATTERN = "[A-Za-z0-9_-]{1,128}";
    public static final String NODE_KEY_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    private ApiValidationRules() {
    }
}
