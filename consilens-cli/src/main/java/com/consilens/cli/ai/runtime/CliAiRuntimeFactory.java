package com.consilens.cli.ai.runtime;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.engine.DefaultApprovalManager;
import com.consilens.ai.conversation.engine.DefaultClarificationManager;
import com.consilens.ai.conversation.engine.DefaultConversationEngine;
import com.consilens.ai.conversation.engine.JacksonPlannerResultParser;
import com.consilens.ai.conversation.engine.LlmPlannerAgent;
import com.consilens.ai.conversation.engine.PlanContextAssembler;
import com.consilens.ai.conversation.engine.PlannerAgent;
import com.consilens.ai.conversation.engine.PlannerBridge;
import com.consilens.ai.conversation.engine.ExampleTemplateStore;
import com.consilens.ai.conversation.engine.PlannerPromptBuilder;
import com.consilens.ai.conversation.engine.ToolRegistryPlannerToolExecutor;
import com.consilens.ai.conversation.engine.TaskActionExecutor;
import com.consilens.ai.conversation.service.ConversationRuntimeAdapter;
import com.consilens.ai.conversation.service.DefaultConversationService;
import com.consilens.ai.spi.ToolRegistryFactory;
import com.consilens.ai.runtime.orchestrator.AiConversationOrchestrator;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiSession;
import com.consilens.cli.ai.AIBackendOptions;
import com.consilens.cli.ai.DefaultConfigCapability;
import com.consilens.cli.ai.DefaultDiagnoseCapability;
import com.consilens.cli.ai.DefaultDiffCapability;
import com.consilens.cli.ai.LLMBackendResolver;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Assembles the CLI-backed AI runtime.
 */
public class CliAiRuntimeFactory {

    public AiConversationRuntime create() {
        return new ConversationRuntimeAdapter(createConversationService());
    }

    public ConversationService createConversationService() {
        AiRuntimePaths paths = new AiRuntimePaths();
        AiSessionStore sessionStore = new FileAiSessionStore(paths);
        AiArtifactStore artifactStore = new FileAiArtifactStore(paths);
        AiMemoryStore memoryStore = new FileAiMemoryStore(paths);
        ExampleTemplateStore exampleTemplateStore = new ExampleTemplateStore(paths.examplesDir());
        DefaultConfigCapability configCapability = new DefaultConfigCapability(exampleTemplateStore);
        DefaultDiffCapability diffCapability = new DefaultDiffCapability(artifactStore, paths);
        DefaultDiagnoseCapability diagnoseCapability = new DefaultDiagnoseCapability(null);
        LLMBackendResolver backendResolver = new LLMBackendResolver();
        PlannerAgent plannerAgent = new LlmPlannerAgent(
                context -> backendResolver.resolve(plannerBackendOptions(context)),
                new PlannerPromptBuilder(exampleTemplateStore),
                new JacksonPlannerResultParser(),
                new ToolRegistryPlannerToolExecutor(ToolRegistryFactory.loadDefault(),
                        context -> plannerAttributes(context == null ? null : context.getSession(), artifactStore, memoryStore)));
        DefaultTaskRegistry taskRegistry = new DefaultTaskRegistry(
                new UseConfigTask(configCapability, sessionStore, artifactStore, memoryStore),
                new PlanConfigTask(configCapability, sessionStore, artifactStore, memoryStore),
                new ValidateConfigTask(configCapability, sessionStore, artifactStore, memoryStore),
                new DryRunConfigTask(configCapability, sessionStore, artifactStore, memoryStore),
                new RunDiffTask(configCapability, diffCapability, diagnoseCapability, new DefaultExecutionApprovalService(),
                        sessionStore, artifactStore, memoryStore),
                new DiagnoseTask(diagnoseCapability, sessionStore, artifactStore, memoryStore),
                new RepairTask(configCapability, sessionStore, artifactStore, memoryStore),
                new RememberMemoryTask(sessionStore, artifactStore, memoryStore),
                new ForgetMemoryTask(sessionStore, artifactStore, memoryStore),
                new ExplainTask(configCapability, sessionStore, artifactStore, memoryStore),
                new DoctorTask(sessionStore, artifactStore, memoryStore)
        );
        return new DefaultConversationService(
                new DefaultConversationEngine(
                        sessionStore,
                        plannerAgent,
                        new PlannerBridge(exampleTemplateStore),
                        new PlanContextAssembler(),
                        new DefaultClarificationManager(),
                        new DefaultApprovalManager(),
                        new TaskActionExecutor(taskRegistry, sessionStore)),
                sessionStore,
                artifactStore,
                memoryStore);
    }

    private static AIBackendOptions plannerBackendOptions(com.consilens.ai.conversation.engine.model.PlannerContext context) {
        Map<String, Object> attributes = context == null || context.getAttributes() == null
                ? Map.of()
                : context.getAttributes();
        return AIBackendOptions.builder()
                .backend(stringAttr(attributes, "backend"))
                .model(stringAttr(attributes, "model"))
                .baseUrl(stringAttr(attributes, "baseUrl"))
                .apiKey(stringAttr(attributes, "apiKey"))
                .timeout(stringAttr(attributes, "timeout"))
                .temperature(doubleAttr(attributes, "temperature"))
                .maxTokens(intAttr(attributes, "maxTokens"))
                .noLlm(booleanAttr(attributes, "noLlm"))
                .build();
    }

    private static Map<String, Object> plannerAttributes(AiSession session,
                                                         AiArtifactStore artifactStore,
                                                         AiMemoryStore memoryStore) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (session == null) {
            return attributes;
        }
        put(attributes, "status", session.getStatus());
        put(attributes, "currentTask", session.getCurrentTask());
        put(attributes, "currentObjective", session.getCurrentObjective());
        put(attributes, "currentConfigArtifactId", session.getCurrentConfigArtifactId());
        put(attributes, "latestRunArtifactId", session.getLatestRunArtifactId());
        put(attributes, "latestDiagnosisArtifactId", session.getLatestDiagnosisArtifactId());
        put(attributes, "latestAuditArtifactId", session.getLatestAuditArtifactId());

        if (session.getCurrentConfigArtifactId() != null && !session.getCurrentConfigArtifactId().isBlank()) {
            artifactStore.read(session.getCurrentConfigArtifactId())
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    .ifPresent(content -> {
                        attributes.put("currentConfigContent", content);
                        attributes.put("currentConfigSummary", summarize(content));
                    });
        }

        attributes.put("memoryFacts", memoryStore.list().stream()
                .limit(20)
                .map(memory -> Map.of(
                        "id", memory.getMemoryId(),
                        "type", memory.getType(),
                        "content", memory.getContent()))
                .collect(Collectors.toList()));
        return attributes;
    }

    private static void put(Map<String, Object> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    private static String stringAttr(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Double doubleAttr(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer intAttr(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean booleanAttr(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return false;
    }

    private static String summarize(String content) {
        String compact = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 240) + "...";
    }
}
