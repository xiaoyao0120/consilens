package com.consilens.agent.core.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class FakeApprovalInput {
    private final String name;

    @JsonCreator
    public FakeApprovalInput(@JsonProperty("name") String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public FakeApprovalInput build() {
            return new FakeApprovalInput(name);
        }
    }
}
