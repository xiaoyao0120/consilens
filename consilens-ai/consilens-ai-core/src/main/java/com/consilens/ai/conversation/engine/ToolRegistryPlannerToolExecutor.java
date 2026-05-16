package com.consilens.ai.conversation.engine;

import com.consilens.ai.chat.ConversationContext;
import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.tool.ToolContext;
import com.consilens.ai.tool.ToolRegistry;
import com.consilens.ai.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Planner tool executor backed by the existing ToolRegistry SPI.
 */
public class ToolRegistryPlannerToolExecutor implements PlannerToolExecutor {

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final Function<PlannerContext, Map<String, Object>> attributeProvider;

    public ToolRegistryPlannerToolExecutor(ToolRegistry registry) {
        this(registry, new ObjectMapper(), context -> Map.of());
    }

    public ToolRegistryPlannerToolExecutor(ToolRegistry registry,
                                           Function<PlannerContext, Map<String, Object>> attributeProvider) {
        this(registry, new ObjectMapper(), attributeProvider);
    }

    ToolRegistryPlannerToolExecutor(ToolRegistry registry,
                                    ObjectMapper objectMapper,
                                    Function<PlannerContext, Map<String, Object>> attributeProvider) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.attributeProvider = attributeProvider;
    }

    @Override
    public List<FunctionDefinition> functions() {
        return registry.toFunctionDefinitions();
    }

    @Override
    public String execute(String toolName, Map<String, Object> input, PlannerContext context) {
        ToolResult result = registry.executeTool(
                toolName,
                objectMapper.valueToTree(input == null ? Map.of() : input),
                ToolContext.builder()
                        .conversation(new ConversationContext())
                        .attributes(attributeProvider.apply(context))
                        .build());
        if (!result.isSuccess()) {
            return result.getContent();
        }
        if (result.getStructuredData() != null) {
            try {
                return objectMapper.writeValueAsString(result.getStructuredData());
            } catch (Exception ignored) {
                return result.getContent();
            }
        }
        return result.getContent();
    }
}
