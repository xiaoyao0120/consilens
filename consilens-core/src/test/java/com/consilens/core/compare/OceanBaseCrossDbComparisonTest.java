package com.consilens.core.compare;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.normalization.NormalizationSpec;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;
import com.consilens.core.diff.DiffResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-database comparison test: MySQL vs OceanBase.
 * Requires both MySQL (port 13306) and OceanBase (port 2881) running.
 */
class OceanBaseCrossDbComparisonTest {

    @Test
    void shouldCompareMySqlToOceanBaseWithChecksum() throws Exception {
        // Source: MySQL
        Map<String, Object> sourceConnection = new LinkedHashMap<>();
        sourceConnection.put("url", "jdbc:mysql://127.0.0.1:13306/mydb?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        sourceConnection.put("username", "root");
        sourceConnection.put("password", "Kuanilens_MySQL_2026!");

        ConnectorConfig sourceConfig = ConnectorConfig.builder()
                .type("mysql")
                .connection(sourceConnection)
                .resource(ResourceLocator.builder().type("table").name("mydb.orders").build())
                .readOptions(ReadOptions.builder().options(new LinkedHashMap<>()).build())
                .build();

        // Target: OceanBase (test tenant, mydb database)
        Map<String, Object> targetConnection = new LinkedHashMap<>();
        targetConnection.put("url", "jdbc:mysql://127.0.0.1:2881/test?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        targetConnection.put("username", "root@test");
        targetConnection.put("password", "Admin123_123");

        ConnectorConfig targetConfig = ConnectorConfig.builder()
                .type("oceanbase")
                .connection(targetConnection)
                .resource(ResourceLocator.builder().type("table").name("mydb.orders").build())
                .readOptions(ReadOptions.builder().options(new LinkedHashMap<>()).build())
                .build();

        CompareRequest request = CompareRequest.builder()
                .source(sourceConfig)
                .target(targetConfig)
                .sourceKeySpec(KeySpec.builder().fields(List.of("order_id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("order_id")).build())
                .sourceComparisons(ComparisonSpec.builder()
                        .fields(List.of("customer_id", "amount", "status"))
                        .build())
                .targetComparisons(ComparisonSpec.builder()
                        .fields(List.of("customer_id", "amount", "status"))
                        .build())
                .sourceFilter(PredicateSpec.builder()
                        .type("sql")
                        .expression("created_at >= '2025-01-01'")
                        .build())
                .targetFilter(PredicateSpec.builder()
                        .type("sql")
                        .expression("created_at >= '2025-01-01'")
                        .build())
                .normalizationSpec(NormalizationSpec.builder().build())
                .strategyPreference(CompareStrategyPreference.builder()
                        .preferredPlans(List.of("pushdown_checksum"))
                        .build())
                .executionOptions(CompareExecutionOptions.builder()
                        .checksumAlgorithm("concat")
                        .maxDifferences(5000L)
                        .build())
                .build();

        CompareRuntime runtime = new DefaultCompareRuntime();
        DiffResult result = runtime.execute(request);

        assertNotNull(result);
        assertEquals(0, result.getDifferenceCount(),
                "MySQL and OceanBase should have identical data, but found " + result.getDifferenceCount() + " differences");
        System.out.println("Cross-database (MySQL vs OceanBase) comparison completed: " + result.getDifferenceCount() + " differences");
        System.out.println("Summary: " + result.getSummary());
    }
}
