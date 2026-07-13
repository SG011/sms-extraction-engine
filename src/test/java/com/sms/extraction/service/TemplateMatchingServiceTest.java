package com.sms.extraction.service;

import com.sms.extraction.domain.CandidateTemplate;
import com.sms.extraction.domain.EntitySnapshot;
import com.sms.extraction.domain.ExtractedValue;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.LearnedTemplate;
import com.sms.extraction.domain.TemplateMatchOutcome;
import com.sms.extraction.domain.TemplateMatchResult;
import com.sms.extraction.repository.TemplateRepository;
import com.sms.extraction.service.impl.TemplateMatchingServiceImpl;
import com.sms.extraction.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateMatchingServiceTest {

    @Mock
    private TemplateRepository templateRepository;

    private TemplateMatchingService templateMatchingService;

    private static final String SENDER_ID = "HDFC-BANK";

    @BeforeEach
    void setUp() {
        templateMatchingService = new TemplateMatchingServiceImpl(templateRepository);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CandidateTemplate buildCandidate(List<ExtractedValue> values, List<String> namesInOrder) {
        return CandidateTemplate.builder()
                .extractedValues(values)
                .entityNamesInOrder(namesInOrder)
                .build();
    }

    private ExtractedValue buildValue(String entityName, String value, int start, int end) {
        return ExtractedValue.builder()
                .entityName(entityName)
                .value(value)
                .startPosition(start)
                .endPosition(end)
                .build();
    }

    private LearnedTemplate buildTemplate(String templateId, String category, String subcategory,
                                           String intent, List<EntitySnapshot> snapshots,
                                           String staticHash, String seqHash, boolean active) {
        return LearnedTemplate.builder()
                .templateId(templateId)
                .senderId(SENDER_ID)
                .category(category)
                .subcategory(subcategory)
                .intent(intent)
                .confidenceScore(0.95)
                .canonicalTemplate("INR {AMOUNT} DEBITED FROM YOUR ACCOUNT")
                .entitySnapshots(snapshots)
                .ordering(List.of("AMOUNT"))
                .staticTextHash(staticHash)
                .placeholderSequenceHash(seqHash)
                .active(active)
                .build();
    }

    // -----------------------------------------------------------------------
    // Static text hash match tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Static text hash match should return the matched template")
    void staticTextHashMatchShouldReturnTemplate() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        ExtractedValue amountValue = buildValue("AMOUNT", "500.00", 4, 10);

        CandidateTemplate candidate = buildCandidate(
                List.of(amountValue),
                List.of("AMOUNT")
        );

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(amountValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("AMOUNT"));

        LearnedTemplate template = buildTemplate("tmpl-001", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                Collections.emptyList(), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchResult().get().getTemplate().getTemplateId()).isEqualTo("tmpl-001");
        assertThat(result.getMatchResult().get().getTemplate().getCategory()).isEqualTo("BANKING");
    }

    @Test
    @DisplayName("Inactive template from static hash match should be skipped")
    void inactiveTemplateFromStaticHashShouldBeSkipped() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        ExtractedValue amountValue = buildValue("AMOUNT", "500.00", 4, 10);

        CandidateTemplate candidate = buildCandidate(
                List.of(amountValue),
                List.of("AMOUNT")
        );

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(amountValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("AMOUNT"));

        // Inactive template
        LearnedTemplate inactiveTemplate = buildTemplate("tmpl-low-conf", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                Collections.emptyList(), staticHash, seqHash, false);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.of(inactiveTemplate));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Placeholder sequence hash match + boundary validation tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Placeholder sequence hash match should return template after boundary validation passes")
    void placeholderSequenceHashShouldReturnTemplateWhenBoundaryValidationPasses() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        ExtractedValue amountValue = buildValue("AMOUNT", "500.00", 4, 10);

        CandidateTemplate candidate = buildCandidate(
                List.of(amountValue),
                List.of("AMOUNT")
        );

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(amountValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("AMOUNT"));

        // REGEX snapshot with boundary conditions that ARE present in the SMS
        EntitySnapshot snapshot = EntitySnapshot.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regex("[0-9]+(?:\\.[0-9]{1,2})?")
                .startAfter("INR")     // "INR" appears before "500.00"
                .endBefore("DEBITED")  // "DEBITED" appears after "500.00"
                .build();

        LearnedTemplate template = buildTemplate("tmpl-001", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                List.of(snapshot), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash)).thenReturn(List.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchResult().get().getTemplate().getTemplateId()).isEqualTo("tmpl-001");
    }

    @Test
    @DisplayName("Boundary validation failure should cause fallback to LLM path (returns empty)")
    void boundaryValidationFailureShouldReturnEmpty() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        ExtractedValue amountValue = buildValue("AMOUNT", "500.00", 4, 10);

        CandidateTemplate candidate = buildCandidate(
                List.of(amountValue),
                List.of("AMOUNT")
        );

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(amountValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("AMOUNT"));

        // Snapshot boundary condition that does NOT match the SMS
        EntitySnapshot snapshot = EntitySnapshot.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regex("[0-9]+(?:\\.[0-9]{1,2})?")
                .startAfter("CREDITED")   // "CREDITED" is NOT in the SMS (it says DEBITED)
                .endBefore("DEBITED")
                .build();

        LearnedTemplate template = buildTemplate("tmpl-001", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                List.of(snapshot), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash)).thenReturn(List.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    @DisplayName("Multiple templates with same category/subcategory/intent should be accepted")
    void multipleTemplatesWithSameCategorySubcategoryIntentShouldBeAccepted() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        ExtractedValue amountValue = buildValue("AMOUNT", "500.00", 4, 10);

        CandidateTemplate candidate = buildCandidate(
                List.of(amountValue),
                List.of("AMOUNT")
        );

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(amountValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("AMOUNT"));

        // Both templates have same category/subcategory/intent
        EntitySnapshot snapshot1 = EntitySnapshot.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regex("[0-9]+(?:\\.[0-9]{1,2})?")
                .startAfter("INR")
                .endBefore("DEBITED")
                .build();

        EntitySnapshot snapshot2 = EntitySnapshot.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regex("[0-9]+(?:\\.[0-9]{1,2})?")
                .startAfter("INR")
                .endBefore("FROM")
                .build();

        LearnedTemplate template1 = buildTemplate("tmpl-001", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                List.of(snapshot1), staticHash, seqHash, true);
        LearnedTemplate template2 = buildTemplate("tmpl-002", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                List.of(snapshot2), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash))
                .thenReturn(List.of(template1, template2));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        // Both pass validation and agree on category/subcategory/intent → return first
        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchResult().get().getTemplate().getCategory()).isEqualTo("BANKING");
    }

    @Test
    @DisplayName("Multiple templates with conflicting categories should return empty")
    void multipleTemplatesWithConflictingCategoriesShouldReturnEmpty() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        ExtractedValue amountValue = buildValue("AMOUNT", "500.00", 4, 10);

        CandidateTemplate candidate = buildCandidate(
                List.of(amountValue),
                List.of("AMOUNT")
        );

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(amountValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("AMOUNT"));

        EntitySnapshot snapshot = EntitySnapshot.builder()
                .name("AMOUNT")
                .type(ExtractionRuleType.REGEX)
                .regex("[0-9]+(?:\\.[0-9]{1,2})?")
                .startAfter("INR")
                .endBefore("DEBITED")
                .build();

        // Two templates — same snapshot boundaries but different categories
        LearnedTemplate template1 = buildTemplate("tmpl-001", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                List.of(snapshot), staticHash, seqHash, true);
        LearnedTemplate template2 = buildTemplate("tmpl-002", "ECOMMERCE", "PAYMENT", "PAYMENT_ALERT",
                List.of(snapshot), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash))
                .thenReturn(List.of(template1, template2));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    @DisplayName("Empty candidate list with no static hash match should return failed")
    void emptyCandidateListShouldReturnEmpty() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        when(templateRepository.findByStaticTextHash(eq(SENDER_ID), anyString())).thenReturn(Optional.empty());

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, Collections.emptyList(), SENDER_ID);

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    @DisplayName("No match in either hash lookup should return empty")
    void noMatchInEitherHashShouldReturnEmpty() {
        String normalizedSms = "INR 500.00 DEBITED FROM YOUR ACCOUNT";
        ExtractedValue amountValue = buildValue("AMOUNT", "500.00", 4, 10);

        CandidateTemplate candidate = buildCandidate(
                List.of(amountValue),
                List.of("AMOUNT")
        );

        when(templateRepository.findByStaticTextHash(anyString(), anyString())).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(anyString(), anyString())).thenReturn(Collections.emptyList());

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    @DisplayName("BOUNDARY_HINT snapshot entities should be boundary-validated")
    void boundaryHintSnapshotEntitiesShouldBeValidated() {
        // "INR 500 DEBITED AT SWIGGY ON 01-01-2024"
        //  0         1         2         3
        //  0123456789012345678901234567890123456789
        // SWIGGY starts at 19, ends at 25
        String normalizedSms = "INR 500 DEBITED AT SWIGGY ON 01-01-2024";
        ExtractedValue merchantValue = buildValue("MERCHANT", "SWIGGY", 19, 25);

        CandidateTemplate candidate = buildCandidate(
                List.of(merchantValue),
                List.of("MERCHANT")
        );

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(merchantValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("MERCHANT"));

        EntitySnapshot snapshot = EntitySnapshot.builder()
                .name("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .regex(null)
                .startAfter("AT")
                .endBefore("ON")
                .build();

        LearnedTemplate template = buildTemplate("tmpl-003", "ECOMMERCE", "PAYMENT", "PURCHASE",
                List.of(snapshot), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash)).thenReturn(List.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        // BOUNDARY_HINT boundaries are now validated — "AT" before and "ON" after SWIGGY → should match
        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchResult().get().getTemplate().getTemplateId()).isEqualTo("tmpl-003");
    }

    // -----------------------------------------------------------------------
    // Zero-entity static hash fallback tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Zero-entity message should match via static hash when active template exists")
    void zeroEntityMessageShouldMatchViaStaticHashWhenTemplateExists() {
        String normalizedSms = "DEAR CUSTOMER YOUR ACCOUNT IS PRE-APPROVED FOR A PERSONAL LOAN";
        String staticHash = HashUtil.staticTextHash(normalizedSms, Collections.emptyList());

        LearnedTemplate template = buildTemplate("tmpl-static-001", "BANKING", "LOAN_OFFER",
                "PROMOTIONAL_OFFER", Collections.emptyList(), staticHash,
                HashUtil.placeholderSequenceHash(Collections.emptyList()), true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, Collections.emptyList(), SENDER_ID);

        assertThat(result.isMatched()).isTrue();
        assertThat(result.getMatchResult().get().getTemplate().getTemplateId()).isEqualTo("tmpl-static-001");
        assertThat(result.getMatchResult().get().getTemplate().getCategory()).isEqualTo("BANKING");
        assertThat(result.getMatchResult().get().getWinningCandidate().getExtractedValues()).isEmpty();
    }

    @Test
    @DisplayName("Zero-entity message with inactive template from static hash should return failed")
    void zeroEntityMessageWithInactiveTemplateShouldReturnFailed() {
        String normalizedSms = "DEAR CUSTOMER YOUR ACCOUNT IS PRE-APPROVED FOR A PERSONAL LOAN";
        String staticHash = HashUtil.staticTextHash(normalizedSms, Collections.emptyList());

        LearnedTemplate inactiveTemplate = buildTemplate("tmpl-low-conf-static", "BANKING", "LOAN_OFFER",
                "PROMOTIONAL_OFFER", Collections.emptyList(), staticHash,
                HashUtil.placeholderSequenceHash(Collections.emptyList()), false);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.of(inactiveTemplate));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, Collections.emptyList(), SENDER_ID);

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    @DisplayName("Zero-entity message with no static hash match should return failed")
    void zeroEntityMessageWithNoStaticHashMatchShouldReturnFailed() {
        String normalizedSms = "DEAR CUSTOMER YOUR ACCOUNT IS PRE-APPROVED FOR A PERSONAL LOAN";
        when(templateRepository.findByStaticTextHash(eq(SENDER_ID), anyString())).thenReturn(Optional.empty());

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, Collections.emptyList(), SENDER_ID);

        assertThat(result.isMatched()).isFalse();
    }

    @Test
    @DisplayName("Boundary validation should pass when endBefore token is followed by a digit not a letter")
    void boundaryValidationShouldPassWhenEndBeforeFollowedByDigit() {
        // TIME endBefore='UPI/P2M/' — followed immediately by digits like 'UPI/P2M/788429438651/'
        String normalizedSms = "INR 80.00 DEBITED ACCOUNT NO. XX7248 09-06-26, 21:59:56 UPI/P2M/788429438651/SRI VANI BOOK DEPOT NOT YOU?";
        ExtractedValue timeValue = buildValue("TIME", "21:59:56", 47, 55);

        CandidateTemplate candidate = buildCandidate(List.of(timeValue), List.of("TIME"));

        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(timeValue));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("TIME"));

        EntitySnapshot snapshot = EntitySnapshot.builder()
                .name("TIME")
                .type(ExtractionRuleType.REGEX)
                .regex("(?<=, )[0-9]{2}:[0-9]{2}:[0-9]{2}")
                .startAfter(",")
                .endBefore("UPI/P2M/")
                .build();

        LearnedTemplate template = buildTemplate("tmpl-upi", "BANKING", "DEBIT", "TRANSACTION_ALERT",
                List.of(snapshot), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash)).thenReturn(List.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        // 'UPI/P2M/' is followed by digits — boundary validation must PASS
        assertThat(result.isMatched()).isTrue();
    }

    @Test
    @DisplayName("SOM as startAfter should always pass boundary validation")
    void somAsStartAfterShouldAlwaysPassBoundaryValidation() {
        String normalizedSms = "CMR GREEN TECHNOLOGIES LIMITED FOR RS29952.00 -SBI";
        ExtractedValue merchant = buildValue("MERCHANT", "CMR GREEN TECHNOLOGIES LIMITED", 0, 30);

        CandidateTemplate candidate = buildCandidate(List.of(merchant), List.of("MERCHANT"));
        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(merchant));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("MERCHANT"));

        EntitySnapshot snapshot = EntitySnapshot.builder()
                .name("MERCHANT").semanticType("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .startAfter("SOM").endBefore("FOR RS")
                .build();

        LearnedTemplate template = buildTemplate("tmpl-som", "BANKING", "MANDATE", "MANDATE_CREATED",
                List.of(snapshot), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash)).thenReturn(List.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isTrue();
    }

    @Test
    @DisplayName("EOM as endBefore should always pass boundary validation")
    void eomAsEndBeforeShouldAlwaysPassBoundaryValidation() {
        String normalizedSms = "UPI MANDATE CREATED TOWARDS CMR GREEN TECHNOLOGIES LIMITED";
        ExtractedValue merchant = buildValue("MERCHANT", "CMR GREEN TECHNOLOGIES LIMITED", 28, 58);

        CandidateTemplate candidate = buildCandidate(List.of(merchant), List.of("MERCHANT"));
        String staticHash = HashUtil.staticTextHash(normalizedSms, List.of(merchant));
        String seqHash = HashUtil.placeholderSequenceHash(List.of("MERCHANT"));

        EntitySnapshot snapshot = EntitySnapshot.builder()
                .name("MERCHANT").semanticType("MERCHANT")
                .type(ExtractionRuleType.BOUNDARY_HINT)
                .startAfter("TOWARDS").endBefore("EOM")
                .build();

        LearnedTemplate template = buildTemplate("tmpl-eom", "BANKING", "MANDATE", "MANDATE_CREATED",
                List.of(snapshot), staticHash, seqHash, true);

        when(templateRepository.findByStaticTextHash(SENDER_ID, staticHash)).thenReturn(Optional.empty());
        when(templateRepository.findByPlaceholderSequenceHash(SENDER_ID, seqHash)).thenReturn(List.of(template));

        TemplateMatchOutcome result = templateMatchingService.match(normalizedSms, List.of(candidate), SENDER_ID);

        assertThat(result.isMatched()).isTrue();
    }
}
