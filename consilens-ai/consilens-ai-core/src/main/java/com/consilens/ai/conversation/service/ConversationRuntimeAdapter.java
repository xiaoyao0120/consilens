package com.consilens.ai.conversation.service;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;

/**
 * Compatibility adapter from the new conversation service to the old runtime interface.
 */
public class ConversationRuntimeAdapter implements AiConversationRuntime {

    private final ConversationService conversationService;

    public ConversationRuntimeAdapter(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @Override
    public AiTurnResult handleUserInput(String sessionId, String input) {
        return toTurnResult(conversationService.sendUserTurn(sessionId, input));
    }

    @Override
    public AiTurnResult executeCommand(String sessionId, AiTaskContext context) {
        ConversationCommandRequest.ConversationCommandRequestBuilder builder = ConversationCommandRequest.builder()
                .sessionId(sessionId);
        if (context != null && context.getCommand() != null) {
            builder.commandName(context.getCommand().getName())
                    .argument(context.getCommand().getArgument());
        }
        if (context != null && context.getAttributes() != null) {
            context.getAttributes().forEach(builder::attribute);
        }
        return toTurnResult(conversationService.executeCommand(builder.build()));
    }

    @Override
    public AiSessionSnapshot snapshot(String sessionId) {
        com.consilens.ai.conversation.api.model.SessionSnapshot snapshot = conversationService.getSessionSnapshot(sessionId);
        return AiSessionSnapshot.builder()
                .session(com.consilens.ai.session.model.AiSession.builder()
                        .sessionId(snapshot.getSessionId())
                        .summary(snapshot.getSummary())
                        .currentObjective(snapshot.getCurrentObjective())
                        .status(snapshot.getStatus())
                        .currentTask(snapshot.getCurrentTask())
                        .currentConfigArtifactId(snapshot.getCurrentConfigArtifactId())
                        .latestRunArtifactId(snapshot.getLatestRunArtifactId())
                        .latestDiagnosisArtifactId(snapshot.getLatestDiagnosisArtifactId())
                        .latestAuditArtifactId(snapshot.getLatestAuditArtifactId())
                        .createdAt(snapshot.getCreatedAt())
                        .updatedAt(snapshot.getUpdatedAt())
                        .build())
                .latestArtifactId(snapshot.getLatestRunArtifactId())
                .build();
    }

    private AiTurnResult toTurnResult(ConversationResponse response) {
        AiTurnResult.Status status;
        switch (response.getType()) {
            case APPROVAL:
                status = AiTurnResult.Status.REQUIRES_APPROVAL;
                break;
            case QUESTION:
                status = AiTurnResult.Status.REQUIRES_CLARIFICATION;
                break;
            case ERROR:
                status = AiTurnResult.Status.FAILED;
                break;
            case MESSAGE:
            default:
                status = AiTurnResult.Status.COMPLETED;
                break;
        }
        return AiTurnResult.builder()
                .status(status)
                .message(response.getMessage())
                .suggestedTask(response.getSuggestedNextStep() == null ? null : response.getSuggestedNextStep().getCode())
                .build();
    }
}
