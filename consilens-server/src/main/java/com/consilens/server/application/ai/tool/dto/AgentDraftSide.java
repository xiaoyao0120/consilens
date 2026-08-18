package com.consilens.server.application.ai.tool.dto;

public enum AgentDraftSide {
    SOURCE,
    TARGET;

    public String draftId() {
        return "draft_" + name().toLowerCase();
    }
}
