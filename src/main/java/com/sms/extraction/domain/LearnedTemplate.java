package com.sms.extraction.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LearnedTemplate {

    private final String templateId;
    private final String senderId;
    private final String category;
    private final String subcategory;
    private final String intent;
    private final double confidenceScore;
    private final String canonicalTemplate;
    private final List<EntitySnapshot> entitySnapshots;
    private final List<String> ordering;
    private final String staticTextHash;
    private final String placeholderSequenceHash;
    private final boolean active;

    private LearnedTemplate(Builder builder) {
        this.templateId = builder.templateId;
        this.senderId = builder.senderId;
        this.category = builder.category;
        this.subcategory = builder.subcategory;
        this.intent = builder.intent;
        this.confidenceScore = builder.confidenceScore;
        this.canonicalTemplate = builder.canonicalTemplate;
        this.entitySnapshots = Collections.unmodifiableList(new ArrayList<>(builder.entitySnapshots));
        this.ordering = Collections.unmodifiableList(new ArrayList<>(builder.ordering));
        this.staticTextHash = builder.staticTextHash;
        this.placeholderSequenceHash = builder.placeholderSequenceHash;
        this.active = builder.active;
    }

    public String getTemplateId() {
        return templateId;
    }

    public String getSenderId() {
        return senderId;
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

    public String getCanonicalTemplate() {
        return canonicalTemplate;
    }

    public List<EntitySnapshot> getEntitySnapshots() {
        return entitySnapshots;
    }

    public List<String> getOrdering() {
        return ordering;
    }

    public String getStaticTextHash() {
        return staticTextHash;
    }

    public String getPlaceholderSequenceHash() {
        return placeholderSequenceHash;
    }

    public boolean isActive() {
        return active;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String templateId;
        private String senderId;
        private String category;
        private String subcategory;
        private String intent;
        private double confidenceScore;
        private String canonicalTemplate;
        private List<EntitySnapshot> entitySnapshots = new ArrayList<>();
        private List<String> ordering = new ArrayList<>();
        private String staticTextHash;
        private String placeholderSequenceHash;
        private boolean active = true;

        private Builder() {}

        public Builder templateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

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

        public Builder canonicalTemplate(String canonicalTemplate) {
            this.canonicalTemplate = canonicalTemplate;
            return this;
        }

        public Builder entitySnapshots(List<EntitySnapshot> entitySnapshots) {
            this.entitySnapshots = entitySnapshots != null ? new ArrayList<>(entitySnapshots) : new ArrayList<>();
            return this;
        }

        public Builder ordering(List<String> ordering) {
            this.ordering = ordering != null ? new ArrayList<>(ordering) : new ArrayList<>();
            return this;
        }

        public Builder staticTextHash(String staticTextHash) {
            this.staticTextHash = staticTextHash;
            return this;
        }

        public Builder placeholderSequenceHash(String placeholderSequenceHash) {
            this.placeholderSequenceHash = placeholderSequenceHash;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public LearnedTemplate build() {
            return new LearnedTemplate(this);
        }
    }

    @Override
    public String toString() {
        return "LearnedTemplate{templateId='" + templateId + "', senderId='" + senderId
                + "', category='" + category + "', subcategory='" + subcategory
                + "', intent='" + intent + "', confidenceScore=" + confidenceScore
                + ", active=" + active + "}";
    }
}
