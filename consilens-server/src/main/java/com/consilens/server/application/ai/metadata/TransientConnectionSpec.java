package com.consilens.server.application.ai.metadata;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Connection spec assembled from a draft + resolved secret; never stored and
 * never visible to the model.
 */
@Value
@Builder
public class TransientConnectionSpec {
    String type;
    String host;
    Integer port;
    String database;
    String username;
    char[] password;
    Map<String, Object> options;
}
