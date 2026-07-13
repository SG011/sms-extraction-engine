package com.sms.extraction.repository;

import com.sms.extraction.domain.NormalizationRuleEntry;

import java.util.List;

public interface NormalizationRuleRepository {

    List<NormalizationRuleEntry> findAll();

    void save(NormalizationRuleEntry entry);

    void deleteByRuleType(String ruleType);
}
