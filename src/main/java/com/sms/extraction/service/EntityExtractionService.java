package com.sms.extraction.service;

import com.sms.extraction.domain.CandidateTemplate;
import com.sms.extraction.domain.GlobalEntity;

import java.util.List;

public interface EntityExtractionService {

    List<CandidateTemplate> extract(String normalizedSms, List<GlobalEntity> entities);
}
