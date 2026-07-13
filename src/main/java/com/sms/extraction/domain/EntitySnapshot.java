package com.sms.extraction.domain;

public final class EntitySnapshot {

    private final String name;
    private final String semanticType;
    private final ExtractionRuleType type;
    private final String regex;
    private final int group;
    private final String startAfter;
    private final String endBefore;
    private final int maxTokens;

    private EntitySnapshot(Builder builder) {
        this.name = builder.name;
        this.semanticType = builder.semanticType;
        this.type = builder.type;
        this.regex = builder.regex;
        this.group = builder.group;
        this.startAfter = builder.startAfter;
        this.endBefore = builder.endBefore;
        this.maxTokens = builder.maxTokens;
    }

    public String getName() { return name; }
    public String getSemanticType() { return semanticType != null ? semanticType : name; }
    public ExtractionRuleType getType() { return type; }
    public String getRegex() { return regex; }
    public int getGroup() { return group; }
    public String getStartAfter() { return startAfter; }
    public String getEndBefore() { return endBefore; }
    public int getMaxTokens() { return maxTokens; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private String semanticType;
        private ExtractionRuleType type;
        private String regex;
        private int group = 0;
        private String startAfter;
        private String endBefore;
        private int maxTokens = 0;

        private Builder() {}

        public Builder name(String name) { this.name = name; return this; }
        public Builder semanticType(String semanticType) { this.semanticType = semanticType; return this; }
        public Builder type(ExtractionRuleType type) { this.type = type; return this; }
        public Builder regex(String regex) { this.regex = regex; return this; }
        public Builder group(int group) { this.group = group; return this; }
        public Builder startAfter(String startAfter) { this.startAfter = startAfter; return this; }
        public Builder endBefore(String endBefore) { this.endBefore = endBefore; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }

        public EntitySnapshot build() { return new EntitySnapshot(this); }
    }

    @Override
    public String toString() {
        return "EntitySnapshot{name='" + name + "', type=" + type + ", regex='" + regex
                + "', startAfter='" + startAfter + "', endBefore='" + endBefore + "}";
    }
}
