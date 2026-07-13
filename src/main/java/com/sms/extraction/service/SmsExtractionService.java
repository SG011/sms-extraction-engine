package com.sms.extraction.service;

import com.sms.extraction.domain.ExtractionResult;

public interface SmsExtractionService {

    ExtractionResult process(String senderId, String rawSms);
}
