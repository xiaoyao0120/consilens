package com.consilens.ai.conversation.error;

/**
 * Stable error codes for conversation responses.
 */
public enum ConversationErrorCode {
    SESSION_NOT_FOUND("session_not_found"),
    CONVERSATION_NO_PENDING_APPROVAL("conversation_no_pending_approval"),
    CONVERSATION_NO_PENDING_QUESTION("conversation_no_pending_question"),
    CONFIG_NOT_FOUND("config_not_found"),
    COMMAND_NAME_REQUIRED("command_name_required"),
    COMMAND_UNSUPPORTED("command_unsupported"),
    APPROVAL_REQUIRED("approval_required"),
    INTERNAL_ERROR("internal_error");

    private final String code;

    ConversationErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
