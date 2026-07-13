package com.sms.extraction.domain;

import java.util.Optional;

public class TemplateMatchOutcome {

    private final TemplateMatchResult matchResult;
    private final LlmReason failReason;

    private TemplateMatchOutcome(TemplateMatchResult matchResult, LlmReason failReason) {
        this.matchResult = matchResult;
        this.failReason = failReason;
    }

    public static TemplateMatchOutcome matched(TemplateMatchResult result) {
        return new TemplateMatchOutcome(result, null);
    }

    public static TemplateMatchOutcome failed(LlmReason reason) {
        return new TemplateMatchOutcome(null, reason);
    }

    public boolean isMatched() {
        return matchResult != null;
    }

    public Optional<TemplateMatchResult> getMatchResult() {
        return Optional.ofNullable(matchResult);
    }

    public LlmReason getFailReason() {
        return failReason;
    }
}
