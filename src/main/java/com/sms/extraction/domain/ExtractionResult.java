package com.sms.extraction.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ExtractionResult {

    private final String messageId;
    private final String senderId;
    private final String rawSms;
    private final String normalizedSms;
    private final String templateId;
    private final String category;
    private final String subcategory;
    private final String intent;
    private final Map<String, String> extractedEntities;
    private final double confidenceScore;
    private final Instant timestamp;

    private ExtractionResult(Builder builder) {
        this.messageId = builder.messageId;
        this.senderId = builder.senderId;
        this.rawSms = builder.rawSms;
        this.normalizedSms = builder.normalizedSms;
        this.templateId = builder.templateId;
        this.category = builder.category;
        this.subcategory = builder.subcategory;
        this.intent = builder.intent;
        this.extractedEntities = Collections.unmodifiableMap(new HashMap<>(builder.extractedEntities));
        this.confidenceScore = builder.confidenceScore;
        this.timestamp = builder.timestamp;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getRawSms() {
        return rawSms;
    }

    public String getNormalizedSms() {
        return normalizedSms;
    }

    public String getTemplateId() {
        return templateId;
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

    public Map<String, String> getExtractedEntities() {
        return extractedEntities;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String messageId;
        private String senderId;
        private String rawSms;
        private String normalizedSms;
        private String templateId;
        private String category;
        private String subcategory;
        private String intent;
        private Map<String, String> extractedEntities = new HashMap<>();
        private double confidenceScore;
        private Instant timestamp;

        private Builder() {}

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder senderId(String senderId) {
            this.senderId = senderId;
            return this;
        }

        public Builder rawSms(String rawSms) {
            this.rawSms = rawSms;
            return this;
        }

        public Builder normalizedSms(String normalizedSms) {
            this.normalizedSms = normalizedSms;
            return this;
        }

        public Builder templateId(String templateId) {
            this.templateId = templateId;
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

        public Builder extractedEntities(Map<String, String> extractedEntities) {
            this.extractedEntities = extractedEntities != null ? new HashMap<>(extractedEntities) : new HashMap<>();
            return this;
        }

        public Builder confidenceScore(double confidenceScore) {
            this.confidenceScore = confidenceScore;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public ExtractionResult build() {
            return new ExtractionResult(this);
        }
    }

    @Override
    public String toString() {
        return "ExtractionResult{messageId='" + messageId + "', senderId='" + senderId
                + "', category='" + category + "', subcategory='" + subcategory
                + "', intent='" + intent + "', confidenceScore=" + confidenceScore + "}";
    }
}
