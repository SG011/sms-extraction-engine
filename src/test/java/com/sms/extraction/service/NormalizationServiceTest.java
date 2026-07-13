package com.sms.extraction.service;

import com.sms.extraction.domain.NormalizationRuleEntry;
import com.sms.extraction.repository.NormalizationRuleRepository;
import com.sms.extraction.service.impl.NormalizationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NormalizationServiceTest {

    @Mock
    private NormalizationRuleRepository normalizationRuleRepository;

    private NormalizationService normalizationService;

    @BeforeEach
    void setUp() {
        normalizationService = new NormalizationServiceImpl(normalizationRuleRepository);
    }

    @Test
    @DisplayName("Should convert raw SMS to uppercase")
    void shouldConvertToUppercase() {
        when(normalizationRuleRepository.findAll()).thenReturn(Collections.emptyList());

        String result = normalizationService.normalize("Your account has been debited");

        assertThat(result).isEqualTo("YOUR ACCOUNT HAS BEEN DEBITED");
    }

    @Test
    @DisplayName("Should apply keyword mapping (Rs. -> INR)")
    void shouldApplyKeywordMapping() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("Rs.", "INR");
        mappings.put("RS.", "INR");

        NormalizationRuleEntry keywordRule = NormalizationRuleEntry.builder()
                .ruleType("KEYWORD_MAPPING")
                .mappings(mappings)
                .build();

        when(normalizationRuleRepository.findAll()).thenReturn(List.of(keywordRule));

        String result = normalizationService.normalize("Debited Rs. 500 from your account");

        assertThat(result).contains("INR");
        assertThat(result).doesNotContain("RS.");
    }

    @Test
    @DisplayName("Should apply multiple keyword mappings")
    void shouldApplyMultipleKeywordMappings() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("Rs.", "INR");
        mappings.put("Rupees", "INR");
        mappings.put("A/C", "ACCOUNT");

        NormalizationRuleEntry keywordRule = NormalizationRuleEntry.builder()
                .ruleType("KEYWORD_MAPPING")
                .mappings(mappings)
                .build();

        when(normalizationRuleRepository.findAll()).thenReturn(List.of(keywordRule));

        String result = normalizationService.normalize("Debited Rupees 500 from A/C 1234");

        assertThat(result).contains("INR 500");
        assertThat(result).contains("ACCOUNT 1234");
    }

    @Test
    @DisplayName("Should collapse multiple spaces into a single space")
    void shouldCollapseMultipleSpaces() {
        when(normalizationRuleRepository.findAll()).thenReturn(Collections.emptyList());

        String result = normalizationService.normalize("Hello   World   Test");

        assertThat(result).isEqualTo("HELLO WORLD TEST");
    }

    @Test
    @DisplayName("Should apply special char rule between digits (/ -> -)")
    void shouldApplySpecialCharRuleBetweenDigits() {
        Map<String, String> specialCharMappings = new HashMap<>();
        specialCharMappings.put("/", "-");

        NormalizationRuleEntry specialCharRule = NormalizationRuleEntry.builder()
                .ruleType("SPECIAL_CHAR")
                .mappings(specialCharMappings)
                .build();

        when(normalizationRuleRepository.findAll()).thenReturn(List.of(specialCharRule));

        String result = normalizationService.normalize("Date: 01/02/2024");

        assertThat(result).contains("01-02-2024");
    }

    @Test
    @DisplayName("Should not apply special char rule when not between digits")
    void shouldNotApplySpecialCharRuleOutsideDigits() {
        Map<String, String> specialCharMappings = new HashMap<>();
        specialCharMappings.put("/", "-");

        NormalizationRuleEntry specialCharRule = NormalizationRuleEntry.builder()
                .ruleType("SPECIAL_CHAR")
                .mappings(specialCharMappings)
                .build();

        when(normalizationRuleRepository.findAll()).thenReturn(List.of(specialCharRule));

        // "/" in "A/C" is between letters, should NOT be replaced
        String result = normalizationService.normalize("Account A/C balance");

        assertThat(result).contains("A/C");
    }

    @Test
    @DisplayName("Should handle null input gracefully")
    void shouldHandleNullInput() {
        // No stubbing needed — early return before repository is called
        String result = normalizationService.normalize(null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should handle empty input gracefully")
    void shouldHandleEmptyInput() {
        // No stubbing needed — early return before repository is called
        String result = normalizationService.normalize("");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should trim leading and trailing spaces after normalization")
    void shouldTrimLeadingAndTrailingSpaces() {
        when(normalizationRuleRepository.findAll()).thenReturn(Collections.emptyList());

        String result = normalizationService.normalize("  Hello World  ");

        assertThat(result).isEqualTo("HELLO WORLD");
    }

    @Test
    @DisplayName("Should apply keyword mapping and then collapse spaces")
    void shouldApplyKeywordMappingAndCollapseSpaces() {
        Map<String, String> mappings = new HashMap<>();
        mappings.put("Rs.", "INR");

        NormalizationRuleEntry keywordRule = NormalizationRuleEntry.builder()
                .ruleType("KEYWORD_MAPPING")
                .mappings(mappings)
                .build();

        when(normalizationRuleRepository.findAll()).thenReturn(List.of(keywordRule));

        String result = normalizationService.normalize("Amount  Rs.  500  paid");

        // After uppercase + mapping + collapse
        assertThat(result).isEqualTo("AMOUNT INR 500 PAID");
    }
}
