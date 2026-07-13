package com.sms.extraction.domain;

public final class LlmEntityInfo {

    private final String name;
    private final String semanticType;
    private final String regex;
    private final int group;
    private final String startAfter;
    private final String endBefore;
    private final int maxTokens;
    private final ExtractionRuleType type;

    private LlmEntityInfo(Builder builder) {
        this.name = builder.name;
        this.semanticType = builder.semanticType;
        this.regex = builder.regex;
        this.group = builder.group;
        this.startAfter = builder.startAfter;
        this.endBefore = builder.endBefore;
        this.maxTokens = builder.maxTokens;
        this.type = builder.type;
    }

    public String getName() { return name; }
    public String getSemanticType() { return semanticType != null ? semanticType : name; }
    public String getRegex() { return regex; }
    public int getGroup() { return group; }
    public String getStartAfter() { return startAfter; }
    public String getEndBefore() { return endBefore; }
    public int getMaxTokens() { return maxTokens; }
    public ExtractionRuleType getType() { return type; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private String semanticType;
        private String regex;
        private int group = 0;
        private String startAfter;
        private String endBefore;
        private int maxTokens;
        private ExtractionRuleType type;

        private Builder() {}

        public Builder name(String name) { this.name = name; return this; }
        public Builder semanticType(String semanticType) { this.semanticType = semanticType; return this; }
        public Builder regex(String regex) { this.regex = regex; return this; }
        public Builder group(int group) { this.group = group; return this; }
        public Builder startAfter(String startAfter) { this.startAfter = startAfter; return this; }
        public Builder endBefore(String endBefore) { this.endBefore = endBefore; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder type(ExtractionRuleType type) { this.type = type; return this; }

        public LlmEntityInfo build() { return new LlmEntityInfo(this); }
    }

    @Override
    public String toString() {
        return "LlmEntityInfo{name='" + name + "', type=" + type + ", regex='" + regex
                + "', startAfter='" + startAfter + "', endBefore='" + endBefore
                + "', maxTokens=" + maxTokens + "}";
    }
}
