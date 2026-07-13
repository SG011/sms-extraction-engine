package com.sms.extraction.domain;

public final class ExtractedValue {

    private final String entityName;
    private final String value;
    private final int startPosition;
    private final int endPosition;

    private ExtractedValue(Builder builder) {
        this.entityName = builder.entityName;
        this.value = builder.value;
        this.startPosition = builder.startPosition;
        this.endPosition = builder.endPosition;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getValue() {
        return value;
    }

    public int getStartPosition() {
        return startPosition;
    }

    public int getEndPosition() {
        return endPosition;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String entityName;
        private String value;
        private int startPosition;
        private int endPosition;

        private Builder() {}

        public Builder entityName(String entityName) {
            this.entityName = entityName;
            return this;
        }

        public Builder value(String value) {
            this.value = value;
            return this;
        }

        public Builder startPosition(int startPosition) {
            this.startPosition = startPosition;
            return this;
        }

        public Builder endPosition(int endPosition) {
            this.endPosition = endPosition;
            return this;
        }

        public ExtractedValue build() {
            return new ExtractedValue(this);
        }
    }

    @Override
    public String toString() {
        return "ExtractedValue{entityName='" + entityName + "', value='" + value
                + "', startPosition=" + startPosition + ", endPosition=" + endPosition + "}";
    }
}
