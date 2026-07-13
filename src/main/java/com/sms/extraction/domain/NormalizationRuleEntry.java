package com.sms.extraction.domain;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class NormalizationRuleEntry {

    private final String ruleType;
    private final Map<String, String> mappings;

    private NormalizationRuleEntry(Builder builder) {
        this.ruleType = builder.ruleType;
        this.mappings = Collections.unmodifiableMap(new HashMap<>(builder.mappings));
    }

    public String getRuleType() {
        return ruleType;
    }

    public Map<String, String> getMappings() {
        return mappings;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String ruleType;
        private Map<String, String> mappings = new HashMap<>();

        private Builder() {}

        public Builder ruleType(String ruleType) {
            this.ruleType = ruleType;
            return this;
        }

        public Builder mappings(Map<String, String> mappings) {
            this.mappings = mappings != null ? new HashMap<>(mappings) : new HashMap<>();
            return this;
        }

        public NormalizationRuleEntry build() {
            return new NormalizationRuleEntry(this);
        }
    }

    @Override
    public String toString() {
        return "NormalizationRuleEntry{ruleType='" + ruleType + "', mappings=" + mappings + "}";
    }
}
