package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

/**
 * Current config contents for a session.
 */
@Value
@Builder
public class CurrentConfigResponse {

    boolean found;
    String artifactId;
    String contentType;
    String content;
}
