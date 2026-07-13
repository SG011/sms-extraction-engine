package com.sms.extraction.service;

import com.sms.extraction.domain.CandidateTemplate;
import com.sms.extraction.domain.TemplateMatchOutcome;

import java.util.List;

public interface TemplateMatchingService {

    TemplateMatchOutcome match(String normalizedSms, List<CandidateTemplate> candidates, String senderId);
}
