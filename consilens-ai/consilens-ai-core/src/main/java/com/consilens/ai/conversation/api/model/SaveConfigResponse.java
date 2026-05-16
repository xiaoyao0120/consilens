package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

/**
 * Result of saving current config content to a file path.
 */
@Value
@Builder
public class SaveConfigResponse {

    boolean saved;
    String artifactId;
    String path;
    String message;
}
