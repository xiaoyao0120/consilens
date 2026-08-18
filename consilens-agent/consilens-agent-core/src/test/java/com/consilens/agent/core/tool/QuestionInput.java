package com.consilens.agent.core.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class QuestionInput {
    private final String question;

    @JsonCreator
    public QuestionInput(@JsonProperty("question") String question) {
        this.question = question;
    }

    public String getQuestion() {
        return question;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String question;

        public Builder question(String value) {
            this.question = value;
            return this;
        }

        public QuestionInput build() {
            return new QuestionInput(question);
        }
    }
}
