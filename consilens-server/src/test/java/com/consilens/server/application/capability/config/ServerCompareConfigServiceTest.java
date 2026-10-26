package com.consilens.server.application.capability.config;

import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.server.api.dto.PlanRequest;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.domain.exception.InvalidInputException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ServerCompareConfigServiceTest {

    private ServerCompareConfigService service() {
        return new ServerCompareConfigService(mock(ArtifactService.class), new ObjectMapper());
    }

    @Test
    void shouldParseFieldAndKeyMappingsFromHints() {
        ServerCompareConfig config = service().fromPlanRequest(planRequestWithHints(Map.of(
                "compareColumns", List.of(
                        Map.of("source", "price_eur", "target", "price"),
                        Map.of("source", "qty", "target", "quantity")),
                "ignoreColumns", List.of("updated_at"),
                "keyMappings", List.of(Map.of("source", "order_id", "target", "id")))));

        ComparisonConfig comparison = config.getComparison();
        assertEquals(2, comparison.getFieldMappings().size());
        assertEquals("price_eur", comparison.getFieldMappings().get(0).getSource());
        assertEquals("price", comparison.getFieldMappings().get(0).getTarget());
        assertEquals(List.of("updated_at"), comparison.getIgnoreColumns());
        assertEquals(1, comparison.getKeyMappings().size());
        assertEquals("order_id", comparison.getKeyMappings().get(0).getSource());
        assertEquals("id", comparison.getKeyMappings().get(0).getTarget());
        assertTrue(comparison.getFields().isEmpty());
    }

    @Test
    void shouldRejectIncompleteComparisonMappings() {
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(EndpointConfig.builder().type("mysql").table("source_table").build())
                .target(EndpointConfig.builder().type("mysql").table("target_table").build())
                .keys(List.of("id"))
                .comparison(ComparisonConfig.builder()
                        .keyMappings(List.of(FieldMapping.builder().source("id").target(" ").build()))
                        .build())
                .build();

        assertThrows(InvalidInputException.class, () -> service().validate(config));
    }

    @Test
    void shouldParsePlainCompareColumnsAsSameNameFields() {
        ServerCompareConfig config = service().fromPlanRequest(planRequestWithHints(Map.of(
                "compareColumns", List.of("name", "status"))));

        ComparisonConfig comparison = config.getComparison();
        assertTrue(comparison.getFieldMappings().isEmpty());
        assertEquals(List.of("name", "status"), comparison.getFields());
    }

    @Test
    void shouldBuildSideSpecificKeyAndComparisonSpecsForMappings() {
        ServerCompareConfig config = service().fromPlanRequest(planRequestWithHints(Map.of(
                "compareColumns", List.of(
                        Map.of("source", "price_eur", "target", "price")),
                "keyMappings", List.of(Map.of("source", "order_id", "target", "id")))));
        config.getSource().setConnection(Map.of("url", "jdbc:mysql://localhost/test"));
        config.getTarget().setConnection(Map.of("url", "jdbc:postgresql://localhost/test"));

        CompareRequest request = service().toCompareRequest(config, new RunRequest.Options());

        assertEquals(List.of("order_id"), request.getSourceKeySpec().getFields());
        assertEquals(List.of("id"), request.getTargetKeySpec().getFields());
        assertEquals(List.of("price_eur"), request.getSourceComparisons().getFields());
        assertEquals(List.of("price"), request.getTargetComparisons().getFields());
    }

    @Test
    void shouldCompileNormalizationFromPlanHints() {
        ServerCompareConfig config = service().fromPlanRequest(planRequestWithHints(Map.of(
                "compareColumns", List.of("amount"),
                "normalization", Map.of(
                        "global", Map.of("decimal", Map.of("precision", 6)),
                        "target", Map.of("timestamp", Map.of("timezone", "UTC"))))));

        config.getSource().setConnection(Map.of("url", "jdbc:mysql://localhost/test"));
        config.getTarget().setConnection(Map.of("url", "jdbc:postgresql://localhost/test"));
        CompareRequest request = service().toCompareRequest(config, new RunRequest.Options());

        assertThat(request.getNormalizationSpec()).isNotNull();
        assertThat(request.getNormalizationSpec().getGlobal()).hasSize(1);
        assertEquals("format_number", request.getNormalizationSpec().getGlobal().get(0).getOperation());
        assertEquals(6, request.getNormalizationSpec().getGlobal().get(0).getParams().get("precision"));
        assertEquals("format_datetime", request.getNormalizationSpec().getTarget().get(0).getOperation());
        assertEquals("UTC", request.getNormalizationSpec().getTarget().get(0).getParams().get("timezone"));
    }

    @Test
    void shouldLeaveNormalizationUnsetWhenNotConfigured() {
        ServerCompareConfig config = service().fromPlanRequest(planRequestWithHints(Map.of(
                "compareColumns", List.of("amount"))));

        config.getSource().setConnection(Map.of("url", "jdbc:mysql://localhost/test"));
        config.getTarget().setConnection(Map.of("url", "jdbc:postgresql://localhost/test"));
        CompareRequest request = service().toCompareRequest(config, new RunRequest.Options());

        assertThat(request.getNormalizationSpec()).isNull();
    }

    @Test
    void shouldKeepTopLevelNormalizationFromUiShapedConfig() {
        ServerCompareConfig config = service().fromContent(Map.of(
                "version", "1.0",
                "goal", "compare orders",
                "source", Map.of("type", "mysql", "table", "orders"),
                "target", Map.of("type", "postgresql", "table", "orders"),
                "keys", List.of("order_id"),
                "comparison", Map.of("fields", List.of("amount")),
                "normalization", Map.of("global", Map.of("decimal", Map.of("precision", 6))),
                "hints", Map.of("normalization", Map.of("global", Map.of("decimal", Map.of("precision", 8))))));

        assertThat(config.getComparison().getNormalization()).isNotNull();
        assertThat(config.getComparison().getNormalization().getGlobal()).containsKey("decimal");
        assertEquals(6, config.getComparison().getNormalization().getGlobal().get("decimal").getPrecision());
    }

    @Test
    void shouldKeepNormalizationFromYamlStringContent() {
        String content = String.join("\n",
                "version: '1.0'",
                "goal: compare orders",
                "source: {type: mysql, table: orders}",
                "target: {type: postgresql, table: orders}",
                "keys: [order_id]",
                "comparison: {fields: [amount]}",
                "normalization:",
                "  global:",
                "    decimal: {precision: 6}",
                "    timestamp: {timezone: UTC}");

        ServerCompareConfig config = service().fromContent(content);

        assertThat(config.getComparison().getNormalization()).isNotNull();
        assertEquals(6, config.getComparison().getNormalization().getGlobal().get("decimal").getPrecision());
        assertEquals("UTC", config.getComparison().getNormalization().getGlobal().get("timestamp").getTimezone());
    }

    @Test
    void shouldRejectMixedCompareColumnFormsInsteadOfDroppingStrings() {
        assertThrows(InvalidInputException.class, () -> service().fromPlanRequest(planRequestWithHints(Map.of(
                "compareColumns", List.of("name", Map.of("source", "a", "target", "b"))))));
    }

    @Test
    void shouldRejectIncompleteKeyMappingInsteadOfDroppingIt() {
        assertThrows(InvalidInputException.class, () -> service().fromPlanRequest(planRequestWithHints(Map.of(
                "compareColumns", List.of("name"),
                "keyMappings", List.of(Map.of("source", "order_id"))))));
    }

    private PlanRequest planRequestWithHints(Map<String, Object> hints) {
        PlanRequest request = new PlanRequest();
        request.setGoal("compare orders");
        request.setSource(endpoint("mysql", "orders"));
        request.setTarget(endpoint("postgresql", "orders"));
        request.setKeys(List.of("order_id"));
        request.setHints(new java.util.LinkedHashMap<>(hints));
        return request;
    }

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
    void shouldRejectFractionalNumericExecutionOption() {
        ServerCompareConfig config = basicConfig(Map.of("bisectionFactor", 4.5));

        assertThrows(InvalidInputException.class,
                () -> service().toCompareRequest(config, new RunRequest.Options()));
    }

    @Test
    void shouldRejectInvalidBooleanExecutionOption() {
        ServerCompareConfig config = basicConfig(Map.of("validateUniqueKeys", "yes"));

        assertThrows(InvalidInputException.class,
                () -> service().toCompareRequest(config, new RunRequest.Options()));
    }

    @Test
    void shouldMapAllowNullableKeysFromExecutionOptions() {
        ServerCompareConfig config = basicConfig(Map.of("allowNullableKeys", true));

        CompareRequest request = service().toCompareRequest(config, new RunRequest.Options());

        assertEquals(Boolean.TRUE, request.getExecutionOptions().getAllowNullableKeys());
    }

    @Test
    void shouldRejectInvalidAllowNullableKeysExecutionOption() {
        ServerCompareConfig config = basicConfig(Map.of("allowNullableKeys", "yes"));

        assertThrows(InvalidInputException.class,
                () -> service().toCompareRequest(config, new RunRequest.Options()));
    }

    private ServerCompareConfig basicConfig(Map<String, Object> executionOptions) {
        return ServerCompareConfig.builder()
                .source(EndpointConfig.builder()
                        .type("mysql").table("orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/test")).build())
                .target(EndpointConfig.builder()
                        .type("postgresql").table("orders")
                        .connection(Map.of("url", "jdbc:postgresql://localhost/test")).build())
                .keys(List.of("id"))
                .executionOptions(executionOptions)
                .build();
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
                + "strategy:\n"
                + "  mode: checksum\n"
                + "  algorithm: concat\n"
                + "  allowNullableKeys: true\n"
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
        assertThat(config.getExecutionOptions()).containsEntry("allowNullableKeys", true);
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
