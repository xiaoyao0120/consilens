package com.consilens.agent.core.tool;

public final class QuestionOutput {
    private final String question;

    public QuestionOutput(String question) {
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

        public QuestionOutput build() {
            return new QuestionOutput(question);
        }
    }
}
