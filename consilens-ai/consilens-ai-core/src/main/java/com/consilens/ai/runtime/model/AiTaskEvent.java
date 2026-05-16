package com.consilens.ai.runtime.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Structured runtime event emitted by an AI task.
 */
@Value
@Builder
public class AiTaskEvent {

    String stage;
    String status;
    String message;
    String artifactId;
    String artifactType;
    Map<String, String> metadata;
}
