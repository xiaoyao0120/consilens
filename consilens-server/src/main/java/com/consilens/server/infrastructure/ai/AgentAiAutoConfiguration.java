package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentSecretStore;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.model.AgentModelClient;
import com.consilens.agent.core.loop.AgentContextAssembler;
import com.consilens.agent.core.loop.AgentRunConfig;
import com.consilens.agent.core.loop.DefaultAgentLoop;
import com.consilens.agent.core.tool.AgentToolRegistry;
import com.consilens.agent.model.openai.OpenAiCompatibleModelClient;
import com.consilens.server.application.ai.AgentRunEnqueueService;
import com.consilens.server.application.ai.AgentRuntime;
import com.consilens.server.application.ai.AgentRuntimeFactory;
import com.consilens.server.application.ai.AgentSecretApplicationService;
import com.consilens.server.application.ai.AgentConversationApplicationService;
import com.consilens.server.application.ai.approval.AgentApprovalService;
import com.consilens.server.application.ai.plan.CreateDatasourceCommandHandler;
import com.consilens.server.application.ai.plan.CreateTaskDefinitionCommandHandler;
import com.consilens.server.application.ai.plan.ProvisioningPlanCompiler;
import com.consilens.server.application.ai.plan.ProvisioningPlanExecutor;
import com.consilens.server.application.ai.plan.ProvisioningActionTransactionService;
import com.consilens.server.application.ai.plan.RunTaskExecutor;
import com.consilens.server.application.ai.tool.CommitProvisioningPlanTool;
import com.consilens.server.application.ai.tool.FindDatasourceTool;
import com.consilens.server.application.ai.tool.GetDatasourceFormSchemaTool;
import com.consilens.server.application.ai.tool.GetTableSchemaTool;
import com.consilens.server.application.ai.tool.GetTaskStatusTool;
import com.consilens.server.application.ai.tool.InspectDraftMetadataTool;
import com.consilens.server.application.ai.tool.ListDatasourcesTool;
import com.consilens.server.application.ai.tool.ListDatasourceTypesTool;
import com.consilens.server.application.ai.tool.PrepareProvisioningPlanTool;
import com.consilens.server.application.ai.tool.ProbeDatasourceTool;
import com.consilens.server.application.ai.tool.RunTaskDefinitionTool;
import com.consilens.server.application.ai.tool.SearchTablesTool;
import com.consilens.server.application.ai.tool.StageCompareDefinitionTool;
import com.consilens.server.application.ai.tool.StageDatasourceDraftTool;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.application.datasource.DataSourceService;
import com.consilens.server.application.ai.metadata.TransientDatasourceMetadataService;
import com.consilens.server.application.task.RunTaskQueryService;
import com.consilens.server.application.taskdefinition.TaskDefinitionService;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.infrastructure.db.mapper.ai.AiRunMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanActionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSecretMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI agent runtime wiring. Only active when
 * {@code consilens.server.ai.enabled=true}; with the default false the
 * existing non-AI API surface is completely untouched.
 */
@Configuration
@ConditionalOnProperty(prefix = "consilens.server.ai", name = "enabled", havingValue = "true")
public class AgentAiAutoConfiguration {

    @Bean
    public OpenAiCompatibleModelClient agentModelClient(AgentRuntimeFactory factory) {
        return factory.createModelClient();
    }

    @Bean
    public AgentSecretCipher agentSecretCipher(ConsilensServerProperties properties) {
        ConsilensServerProperties.Ai ai = properties.getAi();
        String key = ai.getSecretKey();
        if (key == null || key.isBlank()) {
            key = System.getenv(ai.getSecretKeyEnv());
        }
        if (key == null || key.isBlank()) {
            key = System.getProperty(ai.getSecretKeyEnv());
        }
        return new AgentSecretCipher(key, "agent-secret-1");
    }

    @Bean
    public AgentSecretStore agentSecretStore(ConsilensServerProperties properties,
                                             AgentSecretCipher cipher,
                                             AiSecretMapper secretMapper) {
        ConsilensServerProperties.Ai ai = properties.getAi();
        if ("memory".equalsIgnoreCase(ai.getSecretStore())) {
            if (properties.getSecurity().isEnabled()) {
                throw new IllegalStateException(
                        "in-memory secret store is forbidden when security is enabled");
            }
            return new InMemoryAgentSecretStore(cipher);
        }
        return new EncryptedDatabaseAgentSecretStore(secretMapper, cipher);
    }

    @Bean
    public AgentSecretCleanupScheduler agentSecretCleanupScheduler(AiSecretMapper secretMapper) {
        return new AgentSecretCleanupScheduler(secretMapper);
    }

    @Bean
    public AgentRunEnqueueService agentRunEnqueueService(MyBatisAgentPersistence persistence) {
        return new AgentRunEnqueueService(persistence);
    }

    @Bean
    public AgentSecretApplicationService agentSecretApplicationService(
            AgentSecretStore secretStore,
            AgentSecretCipher cipher,
            MyBatisAgentPersistence persistence,
            AgentRunEnqueueService runEnqueueService) {
        return new AgentSecretApplicationService(secretStore, cipher,
                persistence, runEnqueueService);
    }

    @Bean
    public AgentApprovalService agentApprovalService(MyBatisAgentPersistence persistence) {
        return new AgentApprovalService(persistence);
    }

    @Bean
    public AgentConversationApplicationService agentConversationApplicationService(
            MyBatisAgentPersistence persistence,
            AgentRunEnqueueService runEnqueueService,
            AgentApprovalService approvalService) {
        return new AgentConversationApplicationService(persistence, runEnqueueService, approvalService);
    }

    @Bean
    public ProvisioningPlanCompiler provisioningPlanCompiler() {
        return new ProvisioningPlanCompiler();
    }

    @Bean
    public CreateDatasourceCommandHandler createDatasourceCommandHandler(
            DataSourceService dataSourceService,
            AgentSecretStore secretStore,
            MyBatisAgentPersistence persistence) {
        return new CreateDatasourceCommandHandler(dataSourceService, secretStore, persistence);
    }

    @Bean
    public CreateTaskDefinitionCommandHandler createTaskDefinitionCommandHandler(
            TaskDefinitionService taskDefinitionService,
            ServerCompareConfigService configService) {
        return new CreateTaskDefinitionCommandHandler(taskDefinitionService, configService);
    }

    @Bean
    public ProvisioningPlanExecutor provisioningPlanExecutor(
            MyBatisAgentPersistence persistence,
            ProvisioningActionTransactionService transactionService) {
        return new ProvisioningPlanExecutor(persistence, transactionService);
    }

    @Bean
    public RunTaskExecutor runTaskExecutor(MyBatisAgentPersistence persistence,
                                           TaskDefinitionService taskDefinitionService) {
        return new RunTaskExecutor(persistence, taskDefinitionService);
    }

    @Bean
    public ProvisioningActionTransactionService provisioningActionTransactionService(
            CreateDatasourceCommandHandler datasourceHandler,
            CreateTaskDefinitionCommandHandler taskHandler,
            AiPlanActionMapper actionMapper,
            AiPlanMapper planMapper) {
        return new ProvisioningActionTransactionService(datasourceHandler, taskHandler,
                actionMapper, planMapper);
    }

    @Bean
    public AgentToolRegistry agentToolRegistry(DataSourceService dataSourceService,
                                               AgentSecretStore secretStore,
                                               ConnectionTestService connectionTestService,
                                               ConsilensServerProperties properties,
                                               ProvisioningPlanCompiler planCompiler,
                                               AgentPersistence persistence,
                                               AgentApprovalService approvalService,
                                               TaskDefinitionService taskDefinitionService,
                                               RunTaskQueryService queryService,
                                               TransientDatasourceMetadataService metadataService) {
        return new AgentToolRegistry(List.of(
                new ListDatasourceTypesTool(dataSourceService),
                new GetDatasourceFormSchemaTool(dataSourceService),
                new FindDatasourceTool(dataSourceService),
                new ListDatasourcesTool(dataSourceService),
                new SearchTablesTool(dataSourceService),
                new GetTableSchemaTool(dataSourceService),
                new StageDatasourceDraftTool(dataSourceService, secretStore, properties),
                new ProbeDatasourceTool(secretStore, connectionTestService, properties),
                new InspectDraftMetadataTool(metadataService, secretStore),
                new StageCompareDefinitionTool(),
                new PrepareProvisioningPlanTool(planCompiler, persistence),
                new CommitProvisioningPlanTool(persistence, approvalService, planCompiler),
                new RunTaskDefinitionTool(taskDefinitionService, approvalService),
                new GetTaskStatusTool(queryService)));
    }

    @Bean
    public AgentRunConfig agentRunConfig(AgentRuntimeFactory factory) {
        return factory.createRunConfig();
    }

    @Bean
    public AgentContextAssembler agentContextAssembler() {
        return new AgentContextAssembler(loadSystemPrompt(), 100);
    }

    @Bean
    public DefaultAgentLoop agentLoop(MyBatisAgentPersistence persistence,
                                      AgentModelClient modelClient,
                                      AgentToolRegistry registry,
                                      AgentContextAssembler assembler,
                                      AgentRunConfig runConfig) {
        return new DefaultAgentLoop(persistence, modelClient, registry, assembler, runConfig);
    }

    @Bean
    public AgentRuntime agentRuntime(AgentToolRegistry registry,
                                     DefaultAgentLoop loop,
                                     AgentModelClient modelClient,
                                     AgentRunConfig runConfig) {
        return new AgentRuntime(registry, loop, modelClient, runConfig);
    }

    @Bean
    public ServerAgentRunWorker serverAgentRunWorker(MyBatisAgentPersistence persistence,
                                                     DefaultAgentLoop loop,
                                                     AgentRunConfig runConfig,
                                                     ConsilensServerProperties properties,
                                                     ProvisioningPlanExecutor planExecutor,
                                                     RunTaskExecutor runTaskExecutor,
                                                     AgentRunEnqueueService runEnqueueService) {
        return new ServerAgentRunWorker(persistence, loop, runConfig, properties,
                planExecutor, runTaskExecutor, runEnqueueService);
    }

    @Bean
    public ServerAgentRunScheduler serverAgentRunScheduler(AiRunMapper runMapper,
                                                           MyBatisAgentPersistence persistence,
                                                           ConsilensServerProperties properties,
                                                           ServerAgentRunWorker worker) {
        return new ServerAgentRunScheduler(runMapper, persistence, properties, worker);
    }

    @Bean
    @ConditionalOnProperty(prefix = "consilens.server.ai", name = "poller-enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentRunPollingScheduler agentRunPollingScheduler(
            ServerAgentRunScheduler scheduler,
            ConsilensServerProperties properties) {
        String workerId = properties.getNode().getNodeKey();
        if (workerId == null || workerId.isBlank()) {
            workerId = "agent-worker";
        }
        return new AgentRunPollingScheduler(scheduler, workerId);
    }

    @Bean
    public AgentRunRecoveryScheduler agentRunRecoveryScheduler(AiRunMapper runMapper,
                                                               MyBatisAgentPersistence persistence,
                                                               ConsilensServerProperties properties) {
        return new AgentRunRecoveryScheduler(runMapper, persistence, properties);
    }

    @Bean
    public AgentStartupValidator agentStartupValidator(ConsilensServerProperties properties,
                                                        JdbcTemplate jdbcTemplate,
                                                        Environment environment) {
        return new AgentStartupValidator(properties, jdbcTemplate, environment);
    }

    private static String loadSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append(load("ai/prompts/system-base-v1.md"));
        prompt.append('\n').append(load("ai/prompts/safety-v1.md"));
        return prompt.toString();
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing AI prompt resource: " + path, e);
        }
    }
}
