package com.consilens.agent.core.tool;

public final class SecretRefillOutput {
    private final String draftId;
    private final String newSecretRequestId;

    public SecretRefillOutput(String draftId, String newSecretRequestId) {
        this.draftId = draftId;
        this.newSecretRequestId = newSecretRequestId;
    }

    public String getDraftId() {
        return draftId;
    }

    public String getNewSecretRequestId() {
        return newSecretRequestId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String draftId;
        private String newSecretRequestId;

        public Builder draftId(String value) {
            this.draftId = value;
            return this;
        }

        public Builder newSecretRequestId(String value) {
            this.newSecretRequestId = value;
            return this;
        }

        public SecretRefillOutput build() {
            return new SecretRefillOutput(draftId, newSecretRequestId);
        }
    }
}
