package com.consilens.ai.tool;

import com.consilens.ai.spi.ToolProvider;

import java.util.Arrays;
import java.util.List;

/**
 * Provides all default Consilens AI tools.
 */
public class DefaultToolProvider implements ToolProvider {

    @Override
    public String getName() {
        return "defaults";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public List<Tool> getTools() {
        return Arrays.asList(
                new PlannerConfigSchemaTool(),
                new PlannerExtractCompareHintsTool(),
                new PlannerSessionStateTool(),
                new PlannerCurrentConfigTool(),
                new PlannerMemoryFactsTool(),
                new PlannerListConnectorsTool(),
                new PlannerDescribeConnectorTool(),
                new DiffTool(),
                new AnalyzeTool(),
                new ConfigGenerateTool(),
                new RepairGenerateTool(),
                new SchemaDiscoveryTool()
        );
    }
}
