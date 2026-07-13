package com.sms.extraction.metrics;

import com.sms.extraction.domain.LlmReason;

import java.util.Map;

public class MessageRecord {

    private final String messageId;
    private final String senderId;
    private final String rawSms;
    private final String normalizedSms;
    private final String path;
    private final LlmReason llmReason;
    private final String templateId;
    private final String category;
    private final String subcategory;
    private final String intent;
    private final Map<String, String> extractedEntities;
    private final double confidenceScore;
    private final long totalLatencyMs;
    private final Long llmLatencyMs;
    private final Long templateMatchLatencyMs;
    private final String timestamp;

    private MessageRecord(Builder builder) {
        this.messageId = builder.messageId;
        this.senderId = builder.senderId;
        this.rawSms = builder.rawSms;
        this.normalizedSms = builder.normalizedSms;
        this.path = builder.path;
        this.llmReason = builder.llmReason;
        this.templateId = builder.templateId;
        this.category = builder.category;
        this.subcategory = builder.subcategory;
        this.intent = builder.intent;
        this.extractedEntities = builder.extractedEntities;
        this.confidenceScore = builder.confidenceScore;
        this.totalLatencyMs = builder.totalLatencyMs;
        this.llmLatencyMs = builder.llmLatencyMs;
        this.templateMatchLatencyMs = builder.templateMatchLatencyMs;
        this.timestamp = builder.timestamp;
    }

    public String getMessageId()                    { return messageId; }
    public String getSenderId()                     { return senderId; }
    public String getRawSms()                       { return rawSms; }
    public String getNormalizedSms()                { return normalizedSms; }
    public String getPath()                         { return path; }
    public LlmReason getLlmReason()                 { return llmReason; }
    public String getTemplateId()                   { return templateId; }
    public String getCategory()                     { return category; }
    public String getSubcategory()                  { return subcategory; }
    public String getIntent()                       { return intent; }
    public Map<String, String> getExtractedEntities() { return extractedEntities; }
    public double getConfidenceScore()              { return confidenceScore; }
    public long getTotalLatencyMs()                 { return totalLatencyMs; }
    public Long getLlmLatencyMs()                   { return llmLatencyMs; }
    public Long getTemplateMatchLatencyMs()         { return templateMatchLatencyMs; }
    public String getTimestamp()                    { return timestamp; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String messageId;
        private String senderId;
        private String rawSms;
        private String normalizedSms;
        private String path;
        private LlmReason llmReason;
        private String templateId;
        private String category;
        private String subcategory;
        private String intent;
        private Map<String, String> extractedEntities;
        private double confidenceScore;
        private long totalLatencyMs;
        private Long llmLatencyMs;
        private Long templateMatchLatencyMs;
        private String timestamp;

        private Builder() {}

        public Builder messageId(String v)                          { this.messageId = v; return this; }
        public Builder senderId(String v)                           { this.senderId = v; return this; }
        public Builder rawSms(String v)                             { this.rawSms = v; return this; }
        public Builder normalizedSms(String v)                      { this.normalizedSms = v; return this; }
        public Builder path(String v)                               { this.path = v; return this; }
        public Builder llmReason(LlmReason v)                       { this.llmReason = v; return this; }
        public Builder templateId(String v)                         { this.templateId = v; return this; }
        public Builder category(String v)                           { this.category = v; return this; }
        public Builder subcategory(String v)                        { this.subcategory = v; return this; }
        public Builder intent(String v)                             { this.intent = v; return this; }
        public Builder extractedEntities(Map<String, String> v)     { this.extractedEntities = v; return this; }
        public Builder confidenceScore(double v)                    { this.confidenceScore = v; return this; }
        public Builder totalLatencyMs(long v)                       { this.totalLatencyMs = v; return this; }
        public Builder llmLatencyMs(Long v)                         { this.llmLatencyMs = v; return this; }
        public Builder templateMatchLatencyMs(Long v)               { this.templateMatchLatencyMs = v; return this; }
        public Builder timestamp(String v)                          { this.timestamp = v; return this; }

        public MessageRecord build() { return new MessageRecord(this); }
    }
}
