package com.consilens.server.application.capability.config;

import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.exception.InvalidInputException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void shouldParseCliConfigFileStringIntoServerConfig() {
        ServerCompareConfigService service = new ServerCompareConfigService(mock(ArtifactService.class),
                new ObjectMapper());
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-1");
        request.setConfigContent("source:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost/test\n"
                + "    username: demo\n"
                + "    password: demo\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: orders\n"
                + "target:\n"
                + "  type: postgresql\n"
                + "  connection:\n"
                + "    url: jdbc:postgresql://localhost/test\n"
                + "    username: demo\n"
                + "    password: demo\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: orders\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name, amount]\n"
                + "    target: [name, amount]\n"
                + "  filters:\n"
                + "    source: \"id > 0\"\n"
                + "    target: \"id > 0\"\n"
                + "result:\n"
                + "  failOnSinkError: true\n"
                + "  sinks:\n"
                + "    - format: table\n"
                + "      type: result\n"
                + "      properties:\n"
                + "        tableName: dv_job_execution_result\n"
                + "        url: jdbc:mysql://localhost/results");

        ServerCompareConfig config = service.fromRunRequest(request);

        assertThat(config.getSource().getType()).isEqualTo("mysql");
        assertThat(config.getSource().getTable()).isEqualTo("orders");
        assertThat(config.getSource().getFilter()).isEqualTo("id > 0");
        assertThat(config.getSource().getConnection()).containsEntry("url", "jdbc:mysql://localhost/test");
        assertThat(config.getTarget().getType()).isEqualTo("postgresql");
        assertThat(config.getKeys()).containsExactly("id");
        assertThat(config.getComparison().getFields()).containsExactly("name", "amount");
        assertThat(config.getResult().isFailOnSinkError()).isTrue();
        assertThat(config.getResult().getSinks()).hasSize(1);
        assertThat(config.getResult().getSinks().get(0).getProperties())
                .contains("dv_job_execution_result");
    }

    @Test
    void shouldRejectCliConfigFileWithoutTarget() {
        ServerCompareConfigService service = new ServerCompareConfigService(mock(ArtifactService.class),
                new ObjectMapper());
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-1");
        request.setConfigContent("source:\n"
                + "  type: mysql\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: orders\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]");

        assertThrows(InvalidInputException.class, () -> service.fromRunRequest(request));
    }

    @Test
    void shouldRejectRunRequestWithBothArtifactAndContent() {
        ServerCompareConfigService service = new ServerCompareConfigService(mock(ArtifactService.class),
                new ObjectMapper());
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-1");
        request.setConfigArtifactId("artifact-config");
        request.setConfigContent("source: {}");

        assertThrows(InvalidInputException.class, () -> service.fromRunRequest(request));
    }

    private PlanRequest.Endpoint endpoint(String type, String table) {
        PlanRequest.Endpoint endpoint = new PlanRequest.Endpoint();
        endpoint.setType(type);
        endpoint.setTable(table);
        return endpoint;
    }
}
