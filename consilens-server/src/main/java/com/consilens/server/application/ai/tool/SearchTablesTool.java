package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.application.ai.tool.dto.SearchTablesInput;
import com.consilens.server.application.ai.tool.dto.SearchTablesOutput;
import com.consilens.server.application.ai.tool.dto.TableCandidate;
import com.consilens.server.application.datasource.DataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code search_tables}: fuzzy keyword search over persisted datasources.
 * Returns candidate tables (datasource + database + table) so the agent can
 * present a numbered shortlist for the user to confirm before building any
 * drafts — the "vague request -> candidates -> confirmation" path.
 */
public final class SearchTablesTool
        implements AgentTool<SearchTablesInput, SearchTablesOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DATABASES_PER_SOURCE = 8;
    private static final int MAX_CANDIDATES = 50;

    private final DataSourceService dataSourceService;

    public SearchTablesTool(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("search_tables")
                .description("按关键词模糊搜索已有数据源中的库/表，返回候选表列表（数据源+库+表），供用户确认")
                .riskLevel(ToolRiskLevel.LOW)
                .executionMode(ToolExecutionMode.PARALLEL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(30))
                .idempotent(true)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERY,
                        com.consilens.agent.api.state.AgentWorkflowStage.COLLECTING_DATASOURCES,
                        com.consilens.agent.api.state.AgentWorkflowStage.VALIDATING_CONNECTIONS,
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERING_METADATA,
                        com.consilens.agent.api.state.AgentWorkflowStage.DRAFTING_COMPARISON,
                        com.consilens.agent.api.state.AgentWorkflowStage.PLAN_READY))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<SearchTablesInput> inputType() {
        return SearchTablesInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("keyword", MAPPER.createObjectNode().put("type", "string"));
        properties.set("datasourceId", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("keyword"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<SearchTablesOutput> execute(SearchTablesInput input,
                                                        AgentToolContext context) {
        String keyword = input.getKeyword() == null ? "" : input.getKeyword().trim();
        if (keyword.isEmpty()) {
            return AgentToolOutcome.error("TOOL_ARGUMENT_INVALID", false, "keyword 必填");
        }
        String needle = keyword.toLowerCase(Locale.ROOT);
        List<TableCandidate> candidates = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        boolean truncated = false;

        List<DataSourceDto> sources;
        if (input.getDatasourceId() != null && !input.getDatasourceId().isBlank()) {
            DataSourceDto dto = dataSourceService.get(Long.valueOf(input.getDatasourceId()));
            sources = dto == null ? List.of() : List.of(dto);
            if (sources.isEmpty()) {
                return AgentToolOutcome.error("DATASOURCE_NOT_FOUND", false,
                        "数据源不存在: " + input.getDatasourceId());
            }
        } else {
            sources = dataSourceService.list();
        }

        outer:
        for (DataSourceDto source : sources) {
            List<String> databases;
            try {
                databases = dataSourceService.getDatabases(Long.valueOf(source.getId()));
            } catch (Exception e) {
                skipped.add(source.getName());
                continue;
            }
            if (databases.size() > MAX_DATABASES_PER_SOURCE) {
                databases = databases.subList(0, MAX_DATABASES_PER_SOURCE);
            }
            for (String database : databases) {
                List<String> tables;
                try {
                    tables = dataSourceService.getTables(Long.valueOf(source.getId()), database);
                } catch (Exception e) {
                    continue;
                }
                for (String table : tables) {
                    if (!table.toLowerCase(Locale.ROOT).contains(needle)) {
                        continue;
                    }
                    if (candidates.size() >= MAX_CANDIDATES) {
                        truncated = true;
                        break outer;
                    }
                    candidates.add(TableCandidate.builder()
                            .datasourceId(source.getId())
                            .datasourceName(source.getName())
                            .database(database)
                            .table(table)
                            .build());
                }
            }
        }

        return AgentToolOutcome.ok(
                SearchTablesOutput.builder()
                        .candidates(candidates)
                        .truncated(truncated)
                        .skippedDatasources(skipped)
                        .build(),
                candidates.isEmpty() ? "没有找到匹配的表" : "找到 " + candidates.size() + " 个候选表");
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, SearchTablesOutput output) {
        return previous;
    }
}
