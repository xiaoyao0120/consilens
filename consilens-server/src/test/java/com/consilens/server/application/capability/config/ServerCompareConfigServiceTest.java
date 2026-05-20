package com.consilens.server.application.capability.config;

import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.exception.InvalidInputException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ServerCompareConfigServiceTest {

    @Test
    void shouldRejectPlanRequestWithOnlyBlankKeys() {
        ServerCompareConfigService service = new ServerCompareConfigService(mock(ArtifactService.class),
                new ObjectMapper());
        PlanRequest request = new PlanRequest();
        request.setGoal("compare orders");
        request.setSource(endpoint("mysql", "orders"));
        request.setTarget(endpoint("postgresql", "orders"));
        request.setKeys(List.of("  ", ""));

        assertThrows(InvalidInputException.class, () -> service.fromPlanRequest(request));
    }

    @Test
    void shouldRejectInvalidNumericExecutionOption() {
        ServerCompareConfigService service = new ServerCompareConfigService(mock(ArtifactService.class),
                new ObjectMapper());
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(EndpointConfig.builder()
                        .type("mysql")
                        .table("orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/test"))
                        .build())
                .target(EndpointConfig.builder()
                        .type("postgresql")
                        .table("orders")
                        .connection(Map.of("url", "jdbc:postgresql://localhost/test"))
                        .build())
                .keys(List.of("id"))
                .executionOptions(Map.of("bisectionThreshold", "not-a-number"))
                .build();

        assertThrows(InvalidInputException.class, () -> service.toCompareRequest(config, new RunRequest.Options()));
    }

    private PlanRequest.Endpoint endpoint(String type, String table) {
        PlanRequest.Endpoint endpoint = new PlanRequest.Endpoint();
        endpoint.setType(type);
        endpoint.setTable(table);
        return endpoint;
    }
}
