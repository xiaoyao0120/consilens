package com.consilens.agent.core.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SecretRefillInput {
    private final String draftId;

    @JsonCreator
    public SecretRefillInput(@JsonProperty("draftId") String draftId) {
        this.draftId = draftId;
    }

    public String getDraftId() {
        return draftId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String draftId;

        public Builder draftId(String value) {
            this.draftId = value;
            return this;
        }

        public SecretRefillInput build() {
            return new SecretRefillInput(draftId);
        }
    }
}
