package com.sms.extraction.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GlobalEntity {

    private final String name;
    private final ExtractionRuleType type;
    private final List<BoundaryPair> boundaryPairs;
    private final List<RegexVariant> regexVariants;

    private GlobalEntity(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.boundaryPairs = Collections.unmodifiableList(new ArrayList<>(builder.boundaryPairs));
        this.regexVariants = Collections.unmodifiableList(new ArrayList<>(builder.regexVariants));
    }

    public String getName()                     { return name; }
    public ExtractionRuleType getType()         { return type; }
    public List<BoundaryPair> getBoundaryPairs(){ return boundaryPairs; }
    public List<RegexVariant> getRegexVariants(){ return regexVariants; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name;
        private ExtractionRuleType type;
        private List<BoundaryPair> boundaryPairs = new ArrayList<>();
        private List<RegexVariant> regexVariants = new ArrayList<>();

        private Builder() {}

        public Builder name(String name)                              { this.name = name; return this; }
        public Builder type(ExtractionRuleType type)                  { this.type = type; return this; }
        public Builder boundaryPairs(List<BoundaryPair> boundaryPairs){ this.boundaryPairs = boundaryPairs != null ? new ArrayList<>(boundaryPairs) : new ArrayList<>(); return this; }
        public Builder regexVariants(List<RegexVariant> variants)     { this.regexVariants = variants != null ? new ArrayList<>(variants) : new ArrayList<>(); return this; }

        public GlobalEntity build() { return new GlobalEntity(this); }
    }

    @Override
    public String toString() {
        return "GlobalEntity{name='" + name + "', type=" + type + ", regexVariants=" + regexVariants + ", boundaryPairs=" + boundaryPairs + "}";
    }
}
