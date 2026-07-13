package com.sms.extraction.domain;

public final class BoundaryPair {

    private final String startAfter;
    private final String endBefore;
    private final int maxTokens;

    private BoundaryPair(Builder builder) {
        this.startAfter = builder.startAfter;
        this.endBefore = builder.endBefore;
        this.maxTokens = builder.maxTokens;
    }

    public String getStartAfter() { return startAfter; }
    public String getEndBefore() { return endBefore; }
    public int getMaxTokens() { return maxTokens; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String startAfter;
        private String endBefore;
        private int maxTokens;

        private Builder() {}

        public Builder startAfter(String startAfter) { this.startAfter = startAfter; return this; }
        public Builder endBefore(String endBefore) { this.endBefore = endBefore; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }

        public BoundaryPair build() { return new BoundaryPair(this); }
    }

    @Override
    public String toString() {
        return "BoundaryPair{startAfter='" + startAfter + "', endBefore='" + endBefore
                + "', maxTokens=" + maxTokens + "}";
    }
}
