package com.sms.extraction.service;

import com.sms.extraction.domain.BoundaryPair;
import com.sms.extraction.domain.CandidateTemplate;
import com.sms.extraction.domain.ExtractedValue;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.GlobalEntity;
import com.sms.extraction.service.impl.EntityExtractionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class EntityExtractionServiceTest {

    private EntityExtractionService entityExtractionService;

    @BeforeEach
    void setUp() {
        entityExtractionService = new EntityExtractionServiceImpl();
    }

    // -----------------------------------------------------------------------
    // REGEX entity tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("REGEX entity should extract correct value and position")
    void regexEntityShouldExtractCorrectValueAndPosition() {
        GlobalEntity amountEntity = GlobalEntity.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("[0-9]+(?:\\.[0-9]{1,2})?")))
                .boundaryPairs(Collections.emptyList())
                .build();

        String sms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(amountEntity));

        assertThat(candidates).hasSize(1);
        List<ExtractedValue> values = candidates.get(0).getExtractedValues();
        assertThat(values).isNotEmpty();

        ExtractedValue amountValue = values.stream()
                .filter(v -> "AMOUNT".equals(v.getEntityName()))
                .findFirst()
                .orElseThrow();
        assertThat(amountValue.getValue()).isEqualTo("500.00");
        assertThat(amountValue.getStartPosition()).isGreaterThanOrEqualTo(0);
        assertThat(amountValue.getEndPosition()).isGreaterThan(amountValue.getStartPosition());
    }

    @Test
    @DisplayName("REGEX entity should extract OTP value correctly")
    void regexEntityShouldExtractOtpValue() {
        GlobalEntity otpEntity = GlobalEntity.builder()
                .name("OTP")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("\\b[0-9]{4,6}\\b")))
                .boundaryPairs(Collections.emptyList())
                .build();

        String sms = "YOUR OTP IS 123456 DO NOT SHARE";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(otpEntity));

        assertThat(candidates).hasSize(1);
        ExtractedValue otpValue = candidates.get(0).getExtractedValues().stream()
                .filter(v -> "OTP".equals(v.getEntityName()))
                .findFirst()
                .orElseThrow();
        assertThat(otpValue.getValue()).isEqualTo("123456");
    }

    // -----------------------------------------------------------------------
    // BOUNDARY_HINT entity tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BOUNDARY_HINT entity should extract text between startAfter and endBefore")
    void boundaryHintShouldExtractTextBetweenBoundaries() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("AT")
                .endBefore("ON")
                .maxTokens(3)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        String sms = "INR 500 DEBITED AT SWIGGY ON 01-01-2024";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        assertThat(candidates).hasSize(1);
        ExtractedValue merchantValue = candidates.get(0).getExtractedValues().stream()
                .filter(v -> "MERCHANT".equals(v.getEntityName()))
                .findFirst()
                .orElseThrow();
        assertThat(merchantValue.getValue()).isEqualTo("SWIGGY");
    }

    @Test
    @DisplayName("BOUNDARY_HINT with non-empty endBefore that is not found in message should return empty")
    void boundaryHintGreedyFallbackShouldCapturMaxTokensWords() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("AT")
                .endBefore("NONEXISTENTTOKEN")
                .maxTokens(2)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        String sms = "INR 500 DEBITED AT AMAZON FRESH INDIA";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        // endBefore is specified but not found — this boundary pair does not apply, no extraction
        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("Word-boundary matching should prevent ON matching inside AMAZON")
    void wordBoundaryMatchingShouldPreventPartialTokenMatch() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("AT")
                .endBefore("ON")
                .maxTokens(3)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        // "ON" appears inside "AMAZON" — it must NOT match there
        // "ON" also appears as standalone token at the end
        String sms = "INR 500 DEBITED AT AMAZON ON 01-01-2024";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        assertThat(candidates).hasSize(1);
        ExtractedValue merchantValue = candidates.get(0).getExtractedValues().stream()
                .filter(v -> "MERCHANT".equals(v.getEntityName()))
                .findFirst()
                .orElseThrow();
        // "ON" inside "AMAZON" must not be used as endBefore; the standalone "ON" after "AMAZON" must be used
        // Value should be exactly "AMAZON" — endBefore "ON" inside "AMAZON" was not used as a cut point
        assertThat(merchantValue.getValue()).isEqualTo("AMAZON");
    }

    @Test
    @DisplayName("Word-boundary matching: ON should not match inside AMAZON when searching for startAfter")
    void wordBoundaryForStartAfterShouldNotMatchInsideLargerWord() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("PAID")
                .endBefore("USING")
                .maxTokens(3)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        // "PAID" must match as standalone, not inside "PREPAID"
        String sms = "PREPAID RECHARGE PAID AMAZON USING UPI";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        assertThat(candidates).hasSize(1);
        ExtractedValue merchantValue = candidates.get(0).getExtractedValues().stream()
                .filter(v -> "MERCHANT".equals(v.getEntityName()))
                .findFirst()
                .orElseThrow();
        assertThat(merchantValue.getValue()).isEqualTo("AMAZON");
    }

    @Test
    @DisplayName("Empty extraction should return empty list when no entities match")
    void emptyExtractionShouldReturnEmptyList() {
        GlobalEntity entity = GlobalEntity.builder()
                .name("OTP")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("\\b[0-9]{4,6}\\b")))
                .boundaryPairs(Collections.emptyList())
                .build();

        String sms = "NO NUMBERS HERE IN THIS SMS";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(entity));

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("Empty extraction should return empty list when entity list is empty")
    void emptyEntityListShouldReturnEmptyList() {
        String sms = "INR 500 DEBITED FROM ACCOUNT";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, Collections.emptyList());

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("Multiple REGEX entities both matching at non-overlapping positions should produce a candidate")
    void multipleRegexEntitiesMatchingShouldProduceCandidates() {
        GlobalEntity amountEntity = GlobalEntity.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("(?<=INR )[0-9]+(?:\\.[0-9]{1,2})?")))
                .boundaryPairs(Collections.emptyList())
                .build();

        GlobalEntity otpEntity = GlobalEntity.builder()
                .name("OTP")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("(?<=OTP )[0-9]{4,6}")))
                .boundaryPairs(Collections.emptyList())
                .build();

        // AMOUNT and OTP match at different, non-overlapping positions
        String sms = "YOUR OTP 123456 INR 500 DEBITED";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(amountEntity, otpEntity));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getEntityNamesInOrder()).containsExactly("OTP", "AMOUNT");
    }

    @Test
    @DisplayName("Same entity matching at two distinct positions should produce one candidate with both values")
    void sameEntityMatchingAtTwoPositionsShouldProduceOneCandidateWithBothValues() {
        // AMOUNT has two regex variants — each matches at a different position in the message
        com.sms.extraction.domain.RegexVariant totalDueVariant =
                new com.sms.extraction.domain.RegexVariant("(?<=TOTAL DUE: )[0-9]+(?:\\.[0-9]{1,2})?");
        com.sms.extraction.domain.RegexVariant minimumDueVariant =
                new com.sms.extraction.domain.RegexVariant("(?<=MINIMUM DUE: )[0-9]+(?:\\.[0-9]{1,2})?");

        GlobalEntity amountEntity = GlobalEntity.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("(?<=TOTAL DUE: )[0-9]+(?:\\.[0-9]{1,2})?")))
                .regexVariants(List.of(totalDueVariant, minimumDueVariant))
                .boundaryPairs(Collections.emptyList())
                .build();

        String sms = "CARD 1681 : TOTAL DUE: 48795, MINIMUM DUE: 2969.66; PAY BY 25-JUN-26";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(amountEntity));

        // Should be ONE candidate containing BOTH AMOUNT values, not two separate candidates
        assertThat(candidates).hasSize(1);
        List<ExtractedValue> values = candidates.get(0).getExtractedValues();
        List<String> amounts = values.stream()
                .filter(v -> "AMOUNT".equals(v.getEntityName()))
                .map(ExtractedValue::getValue)
                .toList();
        assertThat(amounts).containsExactlyInAnyOrder("48795", "2969.66");
        assertThat(candidates.get(0).getEntityNamesInOrder()).containsExactly("AMOUNT", "AMOUNT");
    }

    @Test
    @DisplayName("BOUNDARY_HINT with empty endBefore should extract maxTokens words greedily")
    void boundaryHintWithEmptyEndBeforeShouldExtractGreedily() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("IF NOT YOU -")
                .endBefore("")
                .maxTokens(2)
                .build();

        GlobalEntity bankNameEntity = GlobalEntity.builder()
                .name("BANK_NAME")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        String sms = "REPORT TO YOUR BANK IF NOT YOU - YES BANK DSVEFQ5MR/O";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(bankNameEntity));

        assertThat(candidates).hasSize(1);
        ExtractedValue value = candidates.get(0).getExtractedValues().stream()
                .filter(v -> "BANK_NAME".equals(v.getEntityName()))
                .findFirst()
                .orElseThrow();
        assertThat(value.getValue()).isEqualTo("YES BANK");
    }

    @Test
    @DisplayName("BOUNDARY_HINT with no matching startAfter returns empty")
    void boundaryHintWithNoStartAfterShouldReturnEmpty() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("NONEXISTENT")
                .endBefore("TOKEN")
                .maxTokens(3)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        String sms = "INR 500 DEBITED AT SWIGGY ON 01-01-2024";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("Entity names in order should reflect position in normalized SMS")
    void entityNamesInOrderShouldReflectPositionOrder() {
        GlobalEntity amountEntity = GlobalEntity.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("INR [0-9]+(?:\\.[0-9]{1,2})?")))
                .boundaryPairs(Collections.emptyList())
                .build();

        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("AT")
                .endBefore("ON")
                .maxTokens(3)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        String sms = "INR 500 DEBITED AT SWIGGY ON 01-01-2024";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(amountEntity, merchantEntity));

        // Primary candidate has both entities; regex-only fallback candidate is also generated
        assertThat(candidates).hasSizeGreaterThanOrEqualTo(1);
        // AMOUNT (INR 500) appears before MERCHANT (SWIGGY) in the SMS
        List<String> namesInOrder = candidates.get(0).getEntityNamesInOrder();
        assertThat(namesInOrder).containsExactly("AMOUNT", "MERCHANT");
    }

    @Test
    @DisplayName("SOM as startAfter should extract from beginning of message")
    void somAsStartAfterShouldExtractFromBeginning() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("SOM")
                .endBefore("FOR RS")
                .maxTokens(5)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        String sms = "CMR GREEN TECHNOLOGIES LIMITED FOR RS29952.00 -SBI";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getExtractedValues().get(0).getValue())
                .isEqualTo("CMR GREEN TECHNOLOGIES LIMITED");
    }

    @Test
    @DisplayName("EOM as endBefore should extract to end of message")
    void eomAsEndBeforeShouldExtractToEndOfMessage() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("TOWARDS")
                .endBefore("EOM")
                .maxTokens(10)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        String sms = "UPI MANDATE CREATED TOWARDS CMR GREEN TECHNOLOGIES LIMITED";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getExtractedValues().get(0).getValue())
                .isEqualTo("CMR GREEN TECHNOLOGIES LIMITED");
    }

    @Test
    @DisplayName("endBefore token ending with letter followed by digit should still be found (not a word boundary violation)")
    void endBeforeTokenFollowedByDigitShouldBeFound() {
        BoundaryPair pair = BoundaryPair.builder()
                .startAfter("TOWARDS")
                .endBefore("FOR RS")
                .maxTokens(5)
                .build();

        GlobalEntity merchantEntity = GlobalEntity.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regexVariants(Collections.emptyList())
                .boundaryPairs(List.of(pair))
                .build();

        // "FOR RS" is immediately followed by digits — must still be found as endBefore
        String sms = "UPI-MANDATE SUCCESSFULLY CREATED TOWARDS CMR GREEN TECHNOLOGIES LIMITED FOR RS29952.00 -SBI";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(merchantEntity));

        assertThat(candidates).hasSize(1);
        ExtractedValue merchant = candidates.get(0).getExtractedValues().stream()
                .filter(v -> "MERCHANT".equals(v.getEntityName()))
                .findFirst().orElseThrow();
        assertThat(merchant.getValue()).isEqualTo("CMR GREEN TECHNOLOGIES LIMITED");
    }

    @Test
    @DisplayName("Two different entities matching at the same position should be alternatives not co-occurrences")
    void differentEntitiesAtSamePositionShouldBeAlternatives() {
        // ACCOUNT_NUMBER uses '(?<=CARD NO. )' — a suffix of 'CREDIT CARD NO. '
        // CARD_NUMBER uses '(?<=CREDIT CARD NO. )' — the full prefix
        // Both match 'XX0980' at the same start position
        GlobalEntity accountEntity = GlobalEntity.builder()
                .name("ACCOUNT_NUMBER")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("(?<=CARD NO\\. )XX[0-9]{4}")))
                .boundaryPairs(Collections.emptyList())
                .build();

        GlobalEntity cardEntity = GlobalEntity.builder()
                .name("CARD_NUMBER")
                .type(ExtractionRuleType.REGEX)
                .regexVariants(List.of(new com.sms.extraction.domain.RegexVariant("(?<=CREDIT CARD NO\\. )XX[0-9]{4}")))
                .boundaryPairs(Collections.emptyList())
                .build();

        String sms = "PAYMENT OF INR 18365.11 FOR AXIS BANK CREDIT CARD NO. XX0980 IS DUE ON 14-06-26";
        List<CandidateTemplate> candidates = entityExtractionService.extract(sms, List.of(accountEntity, cardEntity));

        // Should produce 2 candidates (alternatives), NOT discard due to overlap
        assertThat(candidates).hasSize(2);
        // Each candidate has the amount and one of the card entities
        List<String> candidate0Names = candidates.get(0).getEntityNamesInOrder();
        List<String> candidate1Names = candidates.get(1).getEntityNamesInOrder();
        // One candidate has ACCOUNT_NUMBER, the other has CARD_NUMBER (not both in same candidate)
        boolean firstHasBoth = candidate0Names.contains("ACCOUNT_NUMBER") && candidate0Names.contains("CARD_NUMBER");
        boolean secondHasBoth = candidate1Names.contains("ACCOUNT_NUMBER") && candidate1Names.contains("CARD_NUMBER");
        assertThat(firstHasBoth).isFalse();
        assertThat(secondHasBoth).isFalse();
    }
}
