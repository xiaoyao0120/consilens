package com.consilens.ai.tool;

import com.consilens.core.database.dialect.DialectFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Tool that generates a Consilens YAML configuration file for a given pair of database tables.
 */
@Slf4j
public class ConfigGenerateTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "consilens_config_generate";
    }

    @Override
    public String getDescription() {
        return "Generates a Consilens YAML configuration file for comparing two database tables";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("source_type").put("type", "string").put("description", "Source connector type");
        props.putObject("source_url").put("type", "string").put("description", "Source JDBC URL");
        props.putObject("source_username").put("type", "string").put("description", "Source DB username");
        props.putObject("source_password_env").put("type", "string").put("description", "Source password env variable name");
        props.putObject("source_table").put("type", "string").put("description", "Source table name (schema.table)");
        props.putObject("target_type").put("type", "string").put("description", "Target connector type");
        props.putObject("target_url").put("type", "string").put("description", "Target JDBC URL");
        props.putObject("target_username").put("type", "string").put("description", "Target DB username");
        props.putObject("target_password_env").put("type", "string").put("description", "Target password env variable name");
        props.putObject("target_table").put("type", "string").put("description", "Target table name (schema.table)");
        props.putObject("primary_keys").put("type", "string").put("description", "Comma-separated primary key columns");
        props.putObject("compare_columns").put("type", "string").put("description", "Comma-separated columns to compare (optional, defaults to all)");
        schema.putArray("required").add("source_url").add("source_username").add("source_table")
                .add("target_url").add("target_username").add("target_table").add("primary_keys");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        String sourceUrl = input.path("source_url").asText();
        String sourceUsername = input.path("source_username").asText();
        String sourceType = input.path("source_type").asText();
        String sourcePasswordEnv = input.path("source_password_env").asText("SOURCE_PASSWORD");
        String sourceTable = input.path("source_table").asText();
        String targetUrl = input.path("target_url").asText();
        String targetUsername = input.path("target_username").asText();
        String targetType = input.path("target_type").asText();
        String targetPasswordEnv = input.path("target_password_env").asText("TARGET_PASSWORD");
        String targetTable = input.path("target_table").asText();
        String primaryKeys = input.path("primary_keys").asText();
        String compareColumns = input.path("compare_columns").asText(null);

        if (sourceUrl.isEmpty() || sourceUsername.isEmpty() || targetUrl.isEmpty() || targetUsername.isEmpty()
                || sourceTable.isEmpty() || targetTable.isEmpty() || primaryKeys.isEmpty()) {
            return ToolResult.failure("Required parameters: source_url, source_username, source_table, target_url, target_username, target_table, primary_keys");
        }

        if (sourceType.isBlank()) {
            Optional<String> inferred = inferType(sourceUrl);
            if (inferred.isEmpty()) {
                return ToolResult.failure("Cannot infer source connector type from source_url; please provide source_type.");
            }
            sourceType = inferred.get();
        }
        if (targetType.isBlank()) {
            Optional<String> inferred = inferType(targetUrl);
            if (inferred.isEmpty()) {
                return ToolResult.failure("Cannot infer target connector type from target_url; please provide target_type.");
            }
            targetType = inferred.get();
        }

        String yaml = buildYaml(sourceType, sourceUrl, sourceUsername, sourcePasswordEnv, sourceTable,
                targetType, targetUrl, targetUsername, targetPasswordEnv, targetTable, primaryKeys, compareColumns);

        return ToolResult.success(
                "Generated configuration:\n\n```yaml\n" + yaml + "\n```",
                yaml
        );
    }

    private String buildYaml(String sourceType, String sourceUrl, String sourceUsername, String sourcePasswordEnv,
                              String sourceTable, String targetType, String targetUrl, String targetUsername,
                              String targetPasswordEnv, String targetTable,
                              String primaryKeys, String compareColumns) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Consilens diff configuration\n");
        sb.append("# Generated by Consilens AI\n\n");

        sb.append("source:\n");
        sb.append("  type: ").append(quote(sourceType)).append("\n");
        sb.append("  name: ").append(quote("source-" + sourceType)).append("\n");
        sb.append("  connection:\n");
        sb.append("    url: ").append(quote(sourceUrl)).append("\n");
        sb.append("    username: ").append(quote(sourceUsername)).append("\n");
        sb.append("    password: ").append(quote("${env." + sourcePasswordEnv + "}")).append("\n");
        sb.append("  resource:\n");
        sb.append("    type: table\n");
        sb.append("    name: ").append(quote(sourceTable)).append("\n\n");

        sb.append("target:\n");
        sb.append("  type: ").append(quote(targetType)).append("\n");
        sb.append("  name: ").append(quote("target-" + targetType)).append("\n");
        sb.append("  connection:\n");
        sb.append("    url: ").append(quote(targetUrl)).append("\n");
        sb.append("    username: ").append(quote(targetUsername)).append("\n");
        sb.append("    password: ").append(quote("${env." + targetPasswordEnv + "}")).append("\n");
        sb.append("  resource:\n");
        sb.append("    type: table\n");
        sb.append("    name: ").append(quote(targetTable)).append("\n\n");

        sb.append("comparison:\n");
        sb.append("  keys:\n");
        sb.append("    source:\n");
        for (String pk : primaryKeys.split(",")) {
            sb.append("      - ").append(quote(pk.trim())).append("\n");
        }
        sb.append("    target:\n");
        for (String pk : primaryKeys.split(",")) {
            sb.append("      - ").append(quote(pk.trim())).append("\n");
        }
        if (compareColumns != null && !compareColumns.isEmpty()) {
            sb.append("  fields:\n");
            sb.append("    source:\n");
            for (String col : compareColumns.split(",")) {
                sb.append("      - ").append(quote(col.trim())).append("\n");
            }
            sb.append("    target:\n");
            for (String col : compareColumns.split(",")) {
                sb.append("      - ").append(quote(col.trim())).append("\n");
            }
        }
        sb.append("\nstrategy:\n");
        sb.append("  mode: checksum\n");
        sb.append("  algorithm: xor\n");
        sb.append("\nresult:\n");
        sb.append("  sinks:\n");
        sb.append("    - format: console\n");
        sb.append("      type: result\n");
        return sb.toString();
    }

    private Optional<String> inferType(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String connectorType = DialectFactory.connectorTypeFromJdbcUrl(url);
        // 只做 URL→方言的单一来源推断，不要求该方言已注册到当前 classpath：
        // 生成配置的机器未必装有目标方言插件，注册校验应在执行阶段进行
        if ("unknown".equals(connectorType)) {
            return Optional.empty();
        }
        return Optional.of(connectorType);
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
