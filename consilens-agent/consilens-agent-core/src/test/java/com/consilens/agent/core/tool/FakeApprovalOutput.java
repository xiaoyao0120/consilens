package com.consilens.agent.core.tool;

public final class FakeApprovalOutput {
    private final boolean approved;

    public FakeApprovalOutput(boolean approved) {
        this.approved = approved;
    }

    public boolean isApproved() {
        return approved;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean approved;

        public Builder approved(boolean value) {
            this.approved = value;
            return this;
        }

        public FakeApprovalOutput build() {
            return new FakeApprovalOutput(approved);
        }
    }
}
