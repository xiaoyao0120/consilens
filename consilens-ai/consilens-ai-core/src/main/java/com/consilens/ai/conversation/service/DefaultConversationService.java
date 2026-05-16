package com.consilens.ai.conversation.service;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.api.model.CurrentConfigResponse;
import com.consilens.ai.conversation.api.model.MemoryEntryDto;
import com.consilens.ai.conversation.api.model.PendingApprovalDto;
import com.consilens.ai.conversation.api.model.PendingQuestionDto;
import com.consilens.ai.conversation.api.model.SaveConfigResponse;
import com.consilens.ai.conversation.api.model.SessionRecoveryResponse;
import com.consilens.ai.conversation.api.model.SessionSnapshot;
import com.consilens.ai.conversation.api.model.SessionSummaryDto;
import com.consilens.ai.conversation.engine.ConversationEngine;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import com.consilens.ai.session.model.PendingApprovalState;
import com.consilens.ai.session.model.PendingQuestionState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default high-level conversation service.
 */
public class DefaultConversationService implements ConversationService {

    private final ConversationEngine engine;
    private final AiSessionStore sessionStore;
    private final AiArtifactStore artifactStore;
    private final AiMemoryStore memoryStore;

    public DefaultConversationService(ConversationEngine engine,
                                      AiSessionStore sessionStore,
                                      AiArtifactStore artifactStore,
                                      AiMemoryStore memoryStore) {
        this.engine = engine;
        this.sessionStore = sessionStore;
        this.artifactStore = artifactStore;
        this.memoryStore = memoryStore;
    }

    @Override
    public SessionSnapshot startSession(String preferredSessionId, boolean fresh) {
        AiSession session = fresh
                ? sessionStore.create(preferredSessionId)
                : (preferredSessionId == null || preferredSessionId.isBlank()
                ? sessionStore.create()
                : sessionStore.load(preferredSessionId).orElseGet(() -> sessionStore.create(preferredSessionId)));
        return snapshot(session);
    }

    @Override
    public SessionSnapshot resumeSession(String sessionId) {
        return snapshot(sessionStore.load(sessionId).orElseGet(() -> sessionStore.create(sessionId)));
    }

    @Override
    public List<SessionSummaryDto> listSessions(int limit) {
        return sessionStore.list().stream()
                .limit(Math.max(1, limit))
                .map(this::summary)
                .collect(Collectors.toList());
    }

    @Override
    public ConversationResponse sendUserTurn(String sessionId, String text) {
        return engine.handleUserTurn(sessionId, text);
    }

    @Override
    public ConversationResponse sendUserTurn(String sessionId, String text, java.util.Map<String, Object> attributes) {
        return engine.handleUserTurn(sessionId, text, attributes);
    }

    @Override
    public ConversationResponse executeCommand(ConversationCommandRequest request) {
        return engine.executeCommand(request);
    }

    @Override
    public ConversationResponse approve(String sessionId) {
        return engine.approve(sessionId);
    }

    @Override
    public ConversationResponse deny(String sessionId) {
        return engine.deny(sessionId);
    }

    @Override
    public SessionSnapshot getSessionSnapshot(String sessionId) {
        return snapshot(sessionStore.load(sessionId).orElseGet(() -> sessionStore.create(sessionId)));
    }

    @Override
    public SessionRecoveryResponse recoverSession(String sessionId) {
        SessionSnapshot snapshot = getSessionSnapshot(sessionId);
        ArtifactContentResponse currentConfig = snapshot == null || snapshot.getCurrentConfigArtifactId() == null
                ? ArtifactContentResponse.builder().found(false).build()
                : getArtifact(snapshot.getCurrentConfigArtifactId());
        ArtifactContentResponse diagnosis = snapshot == null || snapshot.getLatestDiagnosisArtifactId() == null
                ? ArtifactContentResponse.builder().found(false).build()
                : getArtifact(snapshot.getLatestDiagnosisArtifactId());
        ArtifactContentResponse audit = snapshot == null || snapshot.getLatestAuditArtifactId() == null
                ? ArtifactContentResponse.builder().found(false).build()
                : getArtifact(snapshot.getLatestAuditArtifactId());
        return SessionRecoveryResponse.builder()
                .session(snapshot)
                .currentConfig(currentConfig)
                .latestDiagnosis(diagnosis)
                .latestAudit(audit)
                .recommendedAction(recommendedAction(snapshot, currentConfig, diagnosis))
                .summary(recoverySummary(snapshot, currentConfig, diagnosis, audit))
                .build();
    }

    @Override
    public CurrentConfigResponse getCurrentConfig(String sessionId) {
        AiSession session = sessionStore.load(sessionId).orElse(null);
        if (session == null || session.getCurrentConfigArtifactId() == null) {
            return CurrentConfigResponse.builder()
                    .found(false)
                    .contentType("text/yaml")
                    .build();
        }
        String artifactId = session.getCurrentConfigArtifactId();
        String content = artifactStore.read(artifactId)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse(null);
        return CurrentConfigResponse.builder()
                .found(content != null)
                .artifactId(artifactId)
                .contentType("text/yaml")
                .content(content)
                .build();
    }

    @Override
    public SaveConfigResponse saveCurrentConfig(String sessionId, String path) {
        if (path == null || path.isBlank()) {
            return SaveConfigResponse.builder()
                    .saved(false)
                    .path(path)
                    .message("Target path is required.")
                    .build();
        }
        CurrentConfigResponse currentConfig = getCurrentConfig(sessionId);
        if (!currentConfig.isFound() || currentConfig.getContent() == null) {
            return SaveConfigResponse.builder()
                    .saved(false)
                    .path(path)
                    .message("No current config available to save.")
                    .build();
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, currentConfig.getContent(), StandardCharsets.UTF_8);
            return SaveConfigResponse.builder()
                    .saved(true)
                    .artifactId(currentConfig.getArtifactId())
                    .path(target.toString())
                    .message("Saved current config to " + target)
                    .build();
        } catch (Exception e) {
            return SaveConfigResponse.builder()
                    .saved(false)
                    .artifactId(currentConfig.getArtifactId())
                    .path(target.toString())
                    .message("Failed to save current config: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public List<MemoryEntryDto> listMemory(String sessionId, int limit) {
        return memoryStore.list().stream()
                .filter(memory -> sessionId == null || sessionId.isBlank()
                        || (memory.getSource() != null && memory.getSource().contains(sessionId)))
                .sorted(Comparator.comparing(AiMemory::getCreatedAt).reversed())
                .limit(Math.max(1, limit))
                .map(memory -> MemoryEntryDto.builder()
                        .id(memory.getMemoryId())
                        .type(memory.getType())
                        .content(memory.getContent())
                        .source(memory.getSource())
                        .createdAt(memory.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<ArtifactEntryDto> listArtifacts(String sessionId, int limit) {
        return artifactStore.list(sessionId).stream()
                .limit(Math.max(1, limit))
                .map(ref -> ArtifactEntryDto.builder()
                        .artifactId(ref.getArtifactId())
                        .sessionId(ref.getSessionId())
                        .type(ref.getType() == null ? null : ref.getType().name())
                        .path(ref.getPath())
                        .sha256(ref.getSha256())
                        .metadata(ref.getMetadata())
                        .createdAt(ref.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public ArtifactContentResponse getArtifact(String artifactId) {
        ArtifactRef ref = artifactStore.get(artifactId).orElse(null);
        if (ref == null) {
            return ArtifactContentResponse.builder()
                    .found(false)
                    .artifactId(artifactId)
                    .contentType("text/plain")
                    .build();
        }
        String content = artifactStore.read(artifactId)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse(null);
        return ArtifactContentResponse.builder()
                .found(content != null)
                .artifactId(ref.getArtifactId())
                .sessionId(ref.getSessionId())
                .type(ref.getType() == null ? null : ref.getType().name())
                .path(ref.getPath())
                .sha256(ref.getSha256())
                .contentType(contentType(ref.getType()))
                .content(content)
                .metadata(ref.getMetadata())
                .createdAt(ref.getCreatedAt())
                .build();
    }

    private SessionSummaryDto summary(AiSession session) {
        return SessionSummaryDto.builder()
                .sessionId(session.getSessionId())
                .summary(session.getSummary())
                .currentObjective(session.getCurrentObjective())
                .status(session.getStatus())
                .currentTask(session.getCurrentTask())
                .lastActiveAt(session.getUpdatedAt())
                .build();
    }

    private String contentType(ArtifactType type) {
        if (type == ArtifactType.CONFIG) {
            return "text/yaml";
        }
        if (type == ArtifactType.DIFF_RESULT || type == ArtifactType.DIFF_EVIDENCE) {
            return "application/json";
        }
        return "text/plain";
    }

    private String recommendedAction(SessionSnapshot snapshot,
                                     ArtifactContentResponse currentConfig,
                                     ArtifactContentResponse diagnosis) {
        if (snapshot == null) {
            return "plan";
        }
        if (snapshot.getPendingApproval() != null) {
            return "approve_execute";
        }
        if (snapshot.getPendingQuestion() != null) {
            return "answer_question";
        }
        if ("needs_attention".equalsIgnoreCase(snapshot.getStatus())) {
            if (diagnosis != null && diagnosis.isFound()) {
                return "repair";
            }
            if (currentConfig != null && currentConfig.isFound()) {
                return "validate";
            }
        }
        if (diagnosis != null && diagnosis.isFound()) {
            return "repair";
        }
        if (snapshot.getCurrentConfigArtifactId() != null && !snapshot.getCurrentConfigArtifactId().isBlank()) {
            return "run";
        }
        return "plan";
    }

    private String recoverySummary(SessionSnapshot snapshot,
                                   ArtifactContentResponse currentConfig,
                                   ArtifactContentResponse diagnosis,
                                   ArtifactContentResponse audit) {
        if (snapshot == null) {
            return "No session state available.";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Recovered session ").append(snapshot.getSessionId())
                .append(" status=").append(snapshot.getStatus())
                .append(" task=").append(snapshot.getCurrentTask());
        if (snapshot.getPendingApproval() != null) {
            builder.append(System.lineSeparator()).append("Pending approval requires /approve execute.");
        }
        if (snapshot.getPendingQuestion() != null) {
            builder.append(System.lineSeparator()).append("Pending question: ").append(snapshot.getPendingQuestion().getQuestion());
        }
        builder.append(System.lineSeparator()).append("Config: ")
                .append(artifactLabel(currentConfig));
        builder.append(System.lineSeparator()).append("Diagnosis: ")
                .append(artifactLabel(diagnosis));
        builder.append(System.lineSeparator()).append("Audit: ")
                .append(artifactLabel(audit));
        builder.append(System.lineSeparator()).append("Recommended action: ")
                .append(recommendedAction(snapshot, currentConfig, diagnosis));
        return builder.toString();
    }

    private String artifactLabel(ArtifactContentResponse artifact) {
        if (artifact == null || !artifact.isFound()) {
            return "(none)";
        }
        if (artifact.getPath() == null || artifact.getPath().isBlank()) {
            return artifact.getArtifactId();
        }
        return artifact.getArtifactId() + " @ " + artifact.getPath();
    }

    private SessionSnapshot snapshot(AiSession session) {
        return SessionSnapshot.builder()
                .sessionId(session.getSessionId())
                .summary(session.getSummary())
                .currentObjective(session.getCurrentObjective())
                .status(session.getStatus())
                .currentTask(session.getCurrentTask())
                .currentConfigArtifactId(session.getCurrentConfigArtifactId())
                .latestRunArtifactId(session.getLatestRunArtifactId())
                .latestDiagnosisArtifactId(session.getLatestDiagnosisArtifactId())
                .latestAuditArtifactId(session.getLatestAuditArtifactId())
                .pendingQuestion(question(session.getPendingQuestion()))
                .pendingApproval(approval(session.getPendingApproval()))
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private PendingQuestionDto question(PendingQuestionState pendingQuestion) {
        if (pendingQuestion == null) {
            return null;
        }
        PendingQuestionDto.PendingQuestionDtoBuilder builder = PendingQuestionDto.builder()
                .question(pendingQuestion.getQuestion())
                .blocking(pendingQuestion.isBlocking())
                .originalRequest(pendingQuestion.getOriginalRequest())
                .createdAt(pendingQuestion.getCreatedAt());
        if (pendingQuestion.getExpectedKeys() != null) {
            pendingQuestion.getExpectedKeys().forEach(builder::expectedKey);
        }
        return builder.build();
    }

    private PendingApprovalDto approval(PendingApprovalState pendingApproval) {
        if (pendingApproval == null) {
            return null;
        }
        return PendingApprovalDto.builder()
                .type(pendingApproval.getType())
                .prompt(pendingApproval.getPrompt())
                .relatedCommandName(pendingApproval.getCommandName())
                .relatedCommandArgument(pendingApproval.getCommandArgument())
                .createdAt(pendingApproval.getCreatedAt())
                .build();
    }
}
