package com.consilens.agent.core.tool;

public final class EchoOutput {
    private final String echoed;

    public EchoOutput(String echoed) {
        this.echoed = echoed;
    }

    public String getEchoed() {
        return echoed;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String echoed;

        public Builder echoed(String value) {
            this.echoed = value;
            return this;
        }

        public EchoOutput build() {
            return new EchoOutput(echoed);
        }
    }
}
