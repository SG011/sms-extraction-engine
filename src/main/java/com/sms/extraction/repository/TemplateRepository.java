package com.sms.extraction.repository;

import com.sms.extraction.domain.LearnedTemplate;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository {

    Optional<LearnedTemplate> findById(String templateId);

    Optional<LearnedTemplate> findByStaticTextHash(String senderId, String hash);

    List<LearnedTemplate> findByPlaceholderSequenceHash(String senderId, String hash);

    void save(LearnedTemplate template);
}
