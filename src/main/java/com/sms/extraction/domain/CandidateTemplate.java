package com.sms.extraction.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CandidateTemplate {

    private final List<ExtractedValue> extractedValues;
    private final List<String> entityNamesInOrder;

    private CandidateTemplate(Builder builder) {
        this.extractedValues = Collections.unmodifiableList(new ArrayList<>(builder.extractedValues));
        this.entityNamesInOrder = Collections.unmodifiableList(new ArrayList<>(builder.entityNamesInOrder));
    }

    public List<ExtractedValue> getExtractedValues() {
        return extractedValues;
    }

    public List<String> getEntityNamesInOrder() {
        return entityNamesInOrder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<ExtractedValue> extractedValues = new ArrayList<>();
        private List<String> entityNamesInOrder = new ArrayList<>();

        private Builder() {}

        public Builder extractedValues(List<ExtractedValue> extractedValues) {
            this.extractedValues = extractedValues != null ? new ArrayList<>(extractedValues) : new ArrayList<>();
            return this;
        }

        public Builder entityNamesInOrder(List<String> entityNamesInOrder) {
            this.entityNamesInOrder = entityNamesInOrder != null ? new ArrayList<>(entityNamesInOrder) : new ArrayList<>();
            return this;
        }

        public CandidateTemplate build() {
            return new CandidateTemplate(this);
        }
    }

    @Override
    public String toString() {
        return "CandidateTemplate{extractedValues=" + extractedValues + ", entityNamesInOrder=" + entityNamesInOrder + "}";
    }
}
