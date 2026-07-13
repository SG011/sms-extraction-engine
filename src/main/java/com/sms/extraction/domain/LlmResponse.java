package com.sms.extraction.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LlmResponse {

    private final String category;
    private final String subcategory;
    private final String intent;
    private final double confidenceScore;
    private final Map<String, String> extractedFields;
    private final String canonicalTemplate;
    private final List<LlmEntityInfo> entities;
    private final List<String> ordering;
    private final long inputTokens;
    private final long outputTokens;
    private final long cachedTokens;
    private final long reasoningTokens;

    private LlmResponse(Builder builder) {
        this.category = builder.category;
        this.subcategory = builder.subcategory;
        this.intent = builder.intent;
        this.confidenceScore = builder.confidenceScore;
        this.extractedFields = Collections.unmodifiableMap(new HashMap<>(builder.extractedFields));
        this.canonicalTemplate = builder.canonicalTemplate;
        this.entities = Collections.unmodifiableList(new ArrayList<>(builder.entities));
        this.ordering = Collections.unmodifiableList(new ArrayList<>(builder.ordering));
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.cachedTokens = builder.cachedTokens;
        this.reasoningTokens = builder.reasoningTokens;
    }

    public String getCategory() {
        return category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public String getIntent() {
        return intent;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public Map<String, String> getExtractedFields() {
        return extractedFields;
    }

    public String getCanonicalTemplate() {
        return canonicalTemplate;
    }

    public List<LlmEntityInfo> getEntities() {
        return entities;
    }

    public List<String> getOrdering() {
        return ordering;
    }

    public long getInputTokens()     { return inputTokens; }
    public long getOutputTokens()    { return outputTokens; }
    public long getCachedTokens()    { return cachedTokens; }
    public long getReasoningTokens() { return reasoningTokens; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String category;
        private String subcategory;
        private String intent;
        private double confidenceScore;
        private Map<String, String> extractedFields = new HashMap<>();
        private String canonicalTemplate;
        private List<LlmEntityInfo> entities = new ArrayList<>();
        private List<String> ordering = new ArrayList<>();
        private long inputTokens;
        private long outputTokens;
        private long cachedTokens;
        private long reasoningTokens;

        private Builder() {}

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder subcategory(String subcategory) {
            this.subcategory = subcategory;
            return this;
        }

        public Builder intent(String intent) {
            this.intent = intent;
            return this;
        }

        public Builder confidenceScore(double confidenceScore) {
            this.confidenceScore = confidenceScore;
            return this;
        }

        public Builder extractedFields(Map<String, String> extractedFields) {
            this.extractedFields = extractedFields != null ? new HashMap<>(extractedFields) : new HashMap<>();
            return this;
        }

        public Builder canonicalTemplate(String canonicalTemplate) {
            this.canonicalTemplate = canonicalTemplate;
            return this;
        }

        public Builder entities(List<LlmEntityInfo> entities) {
            this.entities = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
            return this;
        }

        public Builder ordering(List<String> ordering) {
            this.ordering = ordering != null ? new ArrayList<>(ordering) : new ArrayList<>();
            return this;
        }

        public Builder inputTokens(long inputTokens)         { this.inputTokens = inputTokens;         return this; }
        public Builder outputTokens(long outputTokens)       { this.outputTokens = outputTokens;       return this; }
        public Builder cachedTokens(long cachedTokens)       { this.cachedTokens = cachedTokens;       return this; }
        public Builder reasoningTokens(long reasoningTokens) { this.reasoningTokens = reasoningTokens; return this; }

        public LlmResponse build() {
            return new LlmResponse(this);
        }
    }

    @Override
    public String toString() {
        return "LlmResponse{category='" + category + "', subcategory='" + subcategory
                + "', intent='" + intent + "', confidenceScore=" + confidenceScore
                + ", canonicalTemplate='" + canonicalTemplate + "'}";
    }
}
