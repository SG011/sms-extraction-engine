package com.sms.extraction.service.impl;

import com.sms.extraction.domain.NormalizationRuleEntry;
import com.sms.extraction.repository.NormalizationRuleRepository;
import com.sms.extraction.service.NormalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class NormalizationServiceImpl implements NormalizationService {

    private static final Logger log = LoggerFactory.getLogger(NormalizationServiceImpl.class);

    private static final String RULE_TYPE_KEYWORD_MAPPING = "KEYWORD_MAPPING";
    private static final String RULE_TYPE_SPECIAL_CHAR = "SPECIAL_CHAR";

    private final NormalizationRuleRepository normalizationRuleRepository;

    public NormalizationServiceImpl(NormalizationRuleRepository normalizationRuleRepository) {
        this.normalizationRuleRepository = normalizationRuleRepository;
    }

    @Override
    public String normalize(String rawSms) {
        if (rawSms == null || rawSms.isEmpty()) {
            return "";
        }

        List<NormalizationRuleEntry> rules = normalizationRuleRepository.findAll();

        // Step 0: Replace non-ASCII characters with a space.
        // Banking SMS content (amounts, dates, account numbers, merchant names) is ASCII.
        // Non-ASCII in production is either Devanagari script the extraction engine can't process,
        // or garbled UTF-8-as-Latin-1 (e.g. ₹ → â¹). Both poison the entity store if allowed through.
        String text = rawSms.replaceAll("[^\\x00-\\x7F]+", " ");

        // Step 1: Convert to uppercase
        text = text.toUpperCase();

        // Step 2: Apply keyword mappings — replace exact tokens with canonical forms
        for (NormalizationRuleEntry rule : rules) {
            if (RULE_TYPE_KEYWORD_MAPPING.equals(rule.getRuleType())) {
                text = applyKeywordMappings(text, rule.getMappings());
            }
        }

        // Step 3: Apply special character rules — character substitutions between digits
        for (NormalizationRuleEntry rule : rules) {
            if (RULE_TYPE_SPECIAL_CHAR.equals(rule.getRuleType())) {
                text = applySpecialCharRules(text, rule.getMappings());
            }
        }

        // Step 4: Normalize all whitespace — convert tabs/newlines to spaces, then collapse multiples
        text = text.replaceAll("[\\t\\n\\r\\f]", " ").replaceAll(" {2,}", " ").trim();

        log.debug("Normalized SMS: '{}'", text);
        return text;
    }

    /**
     * Applies keyword-to-canonical-token mappings.
     * Replacements are done at word boundaries so "Rs." is replaced but embedded occurrences
     * in longer tokens are not accidentally replaced.
     * The mapping key is treated as a literal string (not regex) and matched case-insensitively
     * since we've already uppercased the text.
     */
    private String applyKeywordMappings(String text, Map<String, String> mappings) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String keyword = entry.getKey().toUpperCase();
            String canonical = entry.getValue().toUpperCase();
            // Use literal replacement with Pattern.LITERAL to avoid regex interpretation
            text = text.replace(keyword, canonical);
        }
        return text;
    }

    /**
     * Applies special character substitution rules.
     * These rules handle special characters that appear between digits (e.g. "/" → "-" for dates).
     * Each key in the mappings is a special character pattern and the value is its replacement.
     * The rule is applied between digit boundaries: (\d)<char>(\d) → $1<replacement>$2
     */
    private String applySpecialCharRules(String text, Map<String, String> mappings) {
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String specialChar = entry.getKey();
            String replacement = entry.getValue();
            try {
                // Apply the special character rule between digits
                String quotedChar = Pattern.quote(specialChar);
                text = text.replaceAll("(\\d)" + quotedChar + "(\\d)", "$1" + replacement + "$2");
            } catch (Exception e) {
                log.warn("Error applying special char rule for char='{}': {}", specialChar, e.getMessage());
            }
        }
        return text;
    }
}
