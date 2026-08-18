package com.consilens.agent.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveValueGuardTest {

    @Test
    void detectsNestedSensitiveKeysCaseInsensitively() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("host", "prod-db");
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("Password", "s3cret");
        root.put("connection", connection);
        root.put("apiKey", "k-123");
        root.put("username", "ro");

        List<String> paths = SensitiveValueGuard.findSensitivePaths(root);
        assertEquals(2, paths.size());
        assertTrue(paths.contains("connection.Password"));
        assertTrue(paths.contains("apiKey"));
    }

    @Test
    void detectsSensitiveKeysInJsonNode() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("name", "safe");
        node.putObject("properties").put("privateKey", "abc");

        List<String> paths = SensitiveValueGuard.findSensitivePaths(node);
        assertEquals(1, paths.size());
        assertEquals("properties.privateKey", paths.get(0));
    }

    @Test
    void allowsOrdinaryKeys() {
        Map<String, Object> root = Map.of("host", "h", "database", "d", "username", "u");
        assertTrue(SensitiveValueGuard.findSensitivePaths(root).isEmpty());
    }
}
