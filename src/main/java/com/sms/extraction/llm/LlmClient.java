package com.sms.extraction.llm;

import com.sms.extraction.domain.GlobalEntity;
import com.sms.extraction.domain.LlmResponse;

import java.util.List;

public interface LlmClient {

    LlmResponse call(String normalizedSms, List<GlobalEntity> existingEntities);
}
