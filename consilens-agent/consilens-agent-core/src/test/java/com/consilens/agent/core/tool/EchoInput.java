package com.consilens.agent.core.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class EchoInput {
    private final String text;

    @JsonCreator
    public EchoInput(@JsonProperty("text") String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String text;

        public Builder text(String value) {
            this.text = value;
            return this;
        }

        public EchoInput build() {
            return new EchoInput(text);
        }
    }
}
