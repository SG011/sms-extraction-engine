package com.sms.extraction.domain;

public class TemplateMatchResult {

    private final LearnedTemplate template;
    private final CandidateTemplate winningCandidate;

    public TemplateMatchResult(LearnedTemplate template, CandidateTemplate winningCandidate) {
        this.template = template;
        this.winningCandidate = winningCandidate;
    }

    public LearnedTemplate getTemplate() {
        return template;
    }

    public CandidateTemplate getWinningCandidate() {
        return winningCandidate;
    }
}
