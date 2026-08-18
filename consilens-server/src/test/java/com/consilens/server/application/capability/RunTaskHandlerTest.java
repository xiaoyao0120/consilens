package com.consilens.server.application.capability;

import com.consilens.server.support.crypto.SecretProtectorTestKeys;

import com.consilens.core.diff.DiffRow;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.SegmentResult;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ComparisonConfig;
import com.consilens.server.application.capability.config.EndpointConfig;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunTaskHandlerTest {

    @Test
    void missingDatasourceFailsLoudlyDuringInjection() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        when(dataSourceRepository.findById(404L)).thenReturn(java.util.Optional.empty());
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(null, configService, null, dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "source", java.util.Map.of("type", "mysql", "table", "t", "datasourceId", 404),
                        "target", java.util.Map.of("type", "mysql", "table", "t", "datasourceId", 404),
                        "keys", java.util.List.of("id")));

        assertThrows(com.consilens.server.domain.exception.ResourceNotFoundException.class,
                () -> handler.injectConnections(config));
    }

    @Test
    void shouldNotExposeRawDiffValuesByDefault() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        DiffRow row = DiffRow.modified(List.of(1),
                List.of("sensitive-source"),
                List.of("sensitive-target"),
                List.of("name"),
                List.of("name"));

        Map<String, Object> result = handler.diffRow(row);

        assertThat(result).containsKeys("operation", "primaryKey", "metadata");
        assertThat(result).doesNotContainKeys("sourceValues", "targetValues");
    }

    @Test
    void shouldPassOracleSidIntoJdbcUrl() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        com.consilens.server.application.artifact.ArtifactService artifactService =
                mock(com.consilens.server.application.artifact.ArtifactService.class);
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(artifactService, configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());

        com.consilens.server.domain.model.DataSourceRecord ds = com.consilens.server.domain.model.DataSourceRecord.builder()
                .id(6L).name("ds-6").type("oracle")
                .paramJson("{\"host\":\"10.0.0.6\",\"port\":1521,\"sid\":\"ORCLPDB1\","
                        + "\"username\":\"system\",\"password\":\""
                        + SecretProtectorTestKeys.protector().protect("p@ss") + "\"}")
                .build();
        when(dataSourceRepository.findById(6L)).thenReturn(java.util.Optional.of(ds));

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "goal", "check",
                        "source", java.util.Map.of("type", "oracle", "table", "EMP", "datasourceId", 6),
                        "target", java.util.Map.of("type", "oracle", "table", "EMP", "datasourceId", 6),
                        "keys", java.util.List.of("EMPNO")));

        handler.injectConnections(config);

        assertThat(config.getSource().getConnection().get("url"))
                .isEqualTo("jdbc:oracle:thin:@//10.0.0.6:1521/ORCLPDB1");
        assertThat(config.getTarget().getConnection().get("url"))
                .isEqualTo("jdbc:oracle:thin:@//10.0.0.6:1521/ORCLPDB1");
    }

    @Test
    void shouldInjectConnectionFromDatasource() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        com.consilens.server.application.artifact.ArtifactService artifactService =
                mock(com.consilens.server.application.artifact.ArtifactService.class);
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(artifactService, configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());

        com.consilens.server.domain.model.DataSourceRecord ds = com.consilens.server.domain.model.DataSourceRecord.builder()
                .id(5L).name("ds-5").type("mysql")
                .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"orders\","
                        + "\"username\":\"root\",\"password\":\""
                        + SecretProtectorTestKeys.protector().protect("p@ss") + "\"}")
                .build();
        when(dataSourceRepository.findById(5L)).thenReturn(java.util.Optional.of(ds));

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "goal", "check",
                        "source", java.util.Map.of("type", "mysql", "table", "orders", "datasourceId", 5),
                        "target", java.util.Map.of("type", "mysql", "table", "orders", "datasourceId", 5),
                        "keys", java.util.List.of("id")));

        handler.injectConnections(config);

        java.util.Map<String, Object> connection = config.getSource().getConnection();
        assertThat(connection.get("url")).isEqualTo("jdbc:mysql://10.0.0.5:3306/orders");
        assertThat(connection.get("password")).isEqualTo("p@ss");
        assertThat(config.getTarget().getConnection().get("url")).isEqualTo("jdbc:mysql://10.0.0.5:3306/orders");
    }

    @Test
    void shouldPreferEndpointDatabaseOverDatasourceParam() {
        com.consilens.server.domain.repository.DataSourceRepository dataSourceRepository =
                mock(com.consilens.server.domain.repository.DataSourceRepository.class);
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(
                mock(com.consilens.server.application.artifact.ArtifactService.class),
                configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                dataSourceRepository,
                SecretProtectorTestKeys.protector(),
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        when(dataSourceRepository.findById(5L)).thenReturn(java.util.Optional.of(
                com.consilens.server.domain.model.DataSourceRecord.builder()
                        .id(5L).type("mysql")
                        .paramJson("{\"host\":\"10.0.0.5\",\"port\":3306,\"database\":\"param-db\"}")
                        .build()));

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "source", java.util.Map.of("type", "mysql", "table", "t",
                                "datasourceId", 5, "database", "endpoint-db"),
                        "target", java.util.Map.of("type", "mysql", "table", "t"),
                        "keys", java.util.List.of("id")));

        handler.injectConnections(config);

        assertThat(config.getSource().getConnection().get("url"))
                .isEqualTo("jdbc:mysql://10.0.0.5:3306/endpoint-db");
    }

    @Test
    void shouldRevealEncryptedDirectConnectionPassword() {
        com.consilens.server.support.crypto.AesGcmSecretProtector crypto =
                SecretProtectorTestKeys.protector();
        com.consilens.server.application.capability.config.ServerCompareConfigService configService =
                new com.consilens.server.application.capability.config.ServerCompareConfigService(
                        mock(com.consilens.server.application.artifact.ArtifactService.class),
                        new com.fasterxml.jackson.databind.ObjectMapper());
        RunTaskHandler handler = new RunTaskHandler(
                mock(com.consilens.server.application.artifact.ArtifactService.class),
                configService,
                mock(com.consilens.server.domain.repository.TaskRepository.class),
                mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                crypto,
                new com.consilens.server.application.datasource.DialectSupport(),
                new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());

        com.consilens.server.application.capability.config.ServerCompareConfig config =
                configService.fromContent(java.util.Map.of(
                        "version", "1.0",
                        "source", java.util.Map.of("type", "mysql", "table", "t",
                                "connection", java.util.Map.of(
                                        "url", "jdbc:mysql://10.0.0.5:3306/db",
                                        "password", crypto.protect("real-pass"))),
                        "target", java.util.Map.of("type", "mysql", "table", "t"),
                        "keys", java.util.List.of("id")));

        handler.injectConnections(config);

        assertThat(config.getSource().getConnection().get("password")).isEqualTo("real-pass");
    }

    @Test
    void shouldBoundDiffRowsInResultArtifact() {
        RunTaskHandler handler = new RunTaskHandler(null, null, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper());
        List<DiffRow> rows = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            rows.add(DiffRow.added(List.of(i), List.of("v" + i), List.of("id", "value")));
        }
        DiffResult diffResult = DiffResult.builder()
                .differences(rows)
                .completedAt(java.time.Instant.now())
                .build();

        Map<String, Object> content = handler.runResult(diffResult);

        assertThat(content.get("differenceCount")).isEqualTo(2500L);
        assertThat(content.get("differenceSampleSize")).isEqualTo(1000);
        assertThat(content.get("differenceSampleTruncated")).isEqualTo(Boolean.TRUE);
        assertThat((List<?>) content.get("differences")).hasSize(1000);
    }

    @Test
    void shouldPublishServerRunResultsThroughLifecycleBeforeWritingArtifact() {
        ArtifactService artifactService = mock(ArtifactService.class);
        ServerCompareConfigService configService = mock(ServerCompareConfigService.class);
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-1");
        request.setConfigArtifactId("config-1");
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(EndpointConfig.builder().type("mysql").table("source_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/source")).build())
                .target(EndpointConfig.builder().type("mysql").table("target_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/target")).build())
                .keys(List.of("id"))
                .comparison(ComparisonConfig.builder().fields(List.of("amount")).build())
                .build();
        DiffResult result = DiffResult.builder()
                .differences(List.of(DiffRow.added(List.of(1), List.of("1"), List.of("id"))))
                .completedAt(java.time.Instant.now())
                .build();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        RunTaskHandler handler = new RunTaskHandler(artifactService, configService, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            protected CompareRuntime createCompareRuntime() {
                return compareRequest -> result;
            }

            @Override
            protected DiffLifecycle buildLifecycle(ServerCompareConfig ignored) {
                return lifecycle;
            }
        };
        when(configService.fromRunRequest(request)).thenReturn(config);
        when(configService.toCompareRequest(any(), any())).thenReturn(mock(CompareRequest.class));
        when(artifactService.writeArtifact(any(), any(), any(), any(), any()))
                .thenReturn(ArtifactRefDto.builder().id("run-result").type("RUN_RESULT").format("json").build());

        handler.handle(TaskExecutionContext.builder().taskId(1L).instanceKey("task-1").build(), request);

        assertThat(lifecycle.started).isTrue();
        assertThat(lifecycle.publishedDifferences).isTrue();
        assertThat(lifecycle.completed).isTrue();
        assertThat(lifecycle.closed).isTrue();
    }

    @Test
    void shouldWriteConfiguredResultAndDiffRecordTableSinks() throws Exception {
        String url = "jdbc:h2:mem:server_run_sinks;MODE=MySQL;DB_CLOSE_DELAY=-1";
        ResultConfig resultConfig = new ResultConfig();
        resultConfig.setSinks(List.of(tableSink("result", url, "dv_job_execution_result", "["
                        + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64)\"},"
                        + "{\"name\":\"run_status\",\"value\":\"${status}\",\"columnType\":\"VARCHAR(32)\"}]"),
                tableSink("diff-record", url, "dv_actual_values", "["
                        + "{\"name\":\"task_id\",\"value\":\"${taskId}\",\"columnType\":\"VARCHAR(64)\"},"
                        + "{\"name\":\"operation\",\"value\":\"${operation}\",\"columnType\":\"VARCHAR(32)\"}]")));
        RunTaskHandler handler = handlerWithResultConfig(resultConfig, resultWithDifference());

        handler.handle(context(), request());

        assertThat(rowCount(url, "dv_job_execution_result")).isEqualTo(1);
        assertThat(rowCount(url, "dv_actual_values")).isEqualTo(1);
    }

    @Test
    void shouldFailRunWhenSinkCannotOpenAndFailOnSinkErrorIsTrue() {
        ResultConfig resultConfig = invalidSinkConfig(true);
        RunTaskHandler handler = handlerWithResultConfig(resultConfig, resultWithDifference());

        assertThrows(CapabilityExecutionException.class, () -> handler.handle(context(), request()));
    }

    @Test
    void shouldContinueRunWhenSinkCannotOpenAndFailOnSinkErrorIsFalse() {
        ResultConfig resultConfig = invalidSinkConfig(false);
        RunTaskHandler handler = handlerWithResultConfig(resultConfig, resultWithDifference());

        assertDoesNotThrow(() -> handler.handle(context(), request()));
    }

    private RunTaskHandler handlerWithResultConfig(ResultConfig resultConfig, DiffResult result) {
        ArtifactService artifactService = mock(ArtifactService.class);
        ServerCompareConfigService configService = mock(ServerCompareConfigService.class);
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(EndpointConfig.builder().type("mysql").table("source_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/source")).build())
                .target(EndpointConfig.builder().type("mysql").table("target_orders")
                        .connection(Map.of("url", "jdbc:mysql://localhost/target")).build())
                .keys(List.of("id"))
                .comparison(ComparisonConfig.builder().fields(List.of("amount")).build())
                .result(resultConfig)
                .build();
        when(configService.fromRunRequest(any())).thenReturn(config);
        when(configService.toCompareRequest(any(), any())).thenReturn(mock(CompareRequest.class));
        when(artifactService.writeArtifact(any(), any(), any(), any(), any()))
                .thenReturn(ArtifactRefDto.builder().id("run-result").type("RUN_RESULT").format("json").build());
        return new RunTaskHandler(artifactService, configService, null,
                        mock(com.consilens.server.domain.repository.DataSourceRepository.class),
                        SecretProtectorTestKeys.protector(),
                        new com.consilens.server.application.datasource.DialectSupport(),
                        new com.consilens.server.boot.ConsilensServerProperties(),
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            protected CompareRuntime createCompareRuntime() {
                return compareRequest -> result;
            }
        };
    }

    private ResultConfig invalidSinkConfig(boolean failOnSinkError) {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("missing");
        sinkConfig.setType("result");
        ResultConfig resultConfig = new ResultConfig();
        resultConfig.setFailOnSinkError(failOnSinkError);
        resultConfig.setSinks(List.of(sinkConfig));
        return resultConfig;
    }

    private SinkConfig tableSink(String type, String url, String tableName, String columns) {
        SinkConfig sinkConfig = new SinkConfig();
        sinkConfig.setFormat("table");
        sinkConfig.setType(type);
        sinkConfig.setProperties("{"
                + "\"type\":\"mysql\","
                + "\"url\":\"" + url + "\","
                + "\"username\":\"sa\","
                + "\"password\":\"\","
                + "\"driver\":\"org.h2.Driver\","
                + "\"tableName\":\"" + tableName + "\","
                + "\"createTable\":true,"
                + "\"dropIfExists\":true,"
                + "\"columns\":" + columns
                + "}");
        return sinkConfig;
    }

    private DiffResult resultWithDifference() {
        return DiffResult.builder()
                .differences(List.of(DiffRow.added(List.of(1), List.of("1"), List.of("id"))))
                .completedAt(java.time.Instant.now())
                .build();
    }

    private TaskExecutionContext context() {
        return TaskExecutionContext.builder().taskId(1L).instanceKey("task-1").build();
    }

    private RunRequest request() {
        RunRequest request = new RunRequest();
        request.setSerialNo("serial-1");
        request.setConfigArtifactId("config-1");
        return request;
    }

    private int rowCount(String url, String tableName) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static class RecordingLifecycle implements DiffLifecycle {

        private boolean started;
        private boolean publishedDifferences;
        private boolean completed;
        private boolean closed;

        @Override
        public void onDiffStart(DiffContext context) {
            started = true;
        }

        @Override
        public void onSegmentComplete(SegmentResult result) {
        }

        @Override
        public void onDifferencesFound(List<DiffRow> diffs, DiffContext context) {
            publishedDifferences = true;
        }

        @Override
        public void onDiffComplete(DiffResult result, DiffContext context) {
            completed = true;
        }

        @Override
        public void onDiffError(DiffContext context, Throwable error) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
