package com.sms.extraction.service.impl;

import com.sms.extraction.domain.CandidateTemplate;
import com.sms.extraction.domain.EntitySnapshot;
import com.sms.extraction.domain.ExtractedValue;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.LearnedTemplate;
import com.sms.extraction.domain.LlmReason;
import com.sms.extraction.domain.TemplateMatchOutcome;
import com.sms.extraction.domain.TemplateMatchResult;
import com.sms.extraction.repository.TemplateRepository;
import com.sms.extraction.service.TemplateMatchingService;
import com.sms.extraction.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TemplateMatchingServiceImpl implements TemplateMatchingService {

    private static final Logger log = LoggerFactory.getLogger(TemplateMatchingServiceImpl.class);

    private final TemplateRepository templateRepository;

    public TemplateMatchingServiceImpl(TemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Override
    public TemplateMatchOutcome match(String normalizedSms, List<CandidateTemplate> candidates, String senderId) {
        if (candidates == null || candidates.isEmpty()) {
            String staticHash = HashUtil.staticTextHash(normalizedSms, Collections.emptyList());
            Optional<LearnedTemplate> staticMatch = templateRepository.findByStaticTextHash(senderId, staticHash);
            if (staticMatch.isPresent()) {
                if (staticMatch.get().isActive()) {
                    CandidateTemplate empty = CandidateTemplate.builder().build();
                    return TemplateMatchOutcome.matched(new TemplateMatchResult(staticMatch.get(), empty));
                }
                return TemplateMatchOutcome.failed(LlmReason.LOW_CONFIDENCE_TEMPLATE_INACTIVE);
            }
            return TemplateMatchOutcome.failed(LlmReason.NO_EXTRACTION_CANDIDATES);
        }

        LlmReason bestFailReason = LlmReason.NO_TEMPLATE_MATCH_BOTH_HASHES_MISSED;

        for (CandidateTemplate candidate : candidates) {
            CandidateOutcome outcome = matchCandidate(normalizedSms, candidate, senderId);
            if (outcome.result != null) {
                return TemplateMatchOutcome.matched(outcome.result);
            }
            bestFailReason = moreSpecific(bestFailReason, outcome.failReason);
        }

        return TemplateMatchOutcome.failed(bestFailReason);
    }

    private CandidateOutcome matchCandidate(String normalizedSms, CandidateTemplate candidate, String senderId) {
        // Option 1: Static text hash match
        String staticHash = HashUtil.staticTextHash(normalizedSms, candidate.getExtractedValues());
        Optional<LearnedTemplate> staticMatch = templateRepository.findByStaticTextHash(senderId, staticHash);
        if (staticMatch.isPresent()) {
            if (staticMatch.get().isActive()) {
                String tid = staticMatch.get().getTemplateId();
                log.info("STATIC_HASH_HIT senderId={} entities={} templateId={}",
                        senderId, candidate.getEntityNamesInOrder(),
                        tid.length() > 16 ? tid.substring(0, 16) + "..." : tid);
                return CandidateOutcome.matched(new TemplateMatchResult(staticMatch.get(), candidate));
            } else {
                log.info("STATIC_HASH_HIT_INACTIVE senderId={} entities={}", senderId, candidate.getEntityNamesInOrder());
                return CandidateOutcome.failed(LlmReason.LOW_CONFIDENCE_TEMPLATE_INACTIVE);
            }
        }

        // Option 2: Placeholder sequence hash match
        String seqHash = HashUtil.placeholderSequenceHash(candidate.getEntityNamesInOrder());
        List<LearnedTemplate> seqMatches = templateRepository.findByPlaceholderSequenceHash(senderId, seqHash);

        if (seqMatches.isEmpty()) {
            log.info("BOTH_HASHES_MISS senderId={} entities={} staticHash={} seqHash={}",
                    senderId, candidate.getEntityNamesInOrder(),
                    staticHash.substring(0, 12), seqHash.substring(0, 12));
            return CandidateOutcome.failed(LlmReason.NO_TEMPLATE_MATCH_BOTH_HASHES_MISSED);
        }

        log.info("SEQ_HASH_HIT senderId={} entities={} seqMatches={}", senderId,
                candidate.getEntityNamesInOrder(), seqMatches.size());

        // Boundary validation
        List<LearnedTemplate> validated = new ArrayList<>();
        boolean allInactive = seqMatches.stream().noneMatch(LearnedTemplate::isActive);
        for (LearnedTemplate template : seqMatches) {
            if (!template.isActive()) continue;
            if (boundaryValidationPasses(normalizedSms, template, candidate)) {
                validated.add(template);
            }
        }

        if (validated.isEmpty()) {
            if (allInactive) {
                return CandidateOutcome.failed(LlmReason.LOW_CONFIDENCE_TEMPLATE_INACTIVE);
            }
            // Strict boundary validation failed — try sequence + word count fallback
            log.info("BOUNDARY_VALIDATION_FAILED senderId={} entities={} — trying sequence fallback",
                    senderId, candidate.getEntityNamesInOrder());
            CandidateOutcome fallback = trySequenceFallback(normalizedSms, seqMatches, candidate);
            if (fallback.result != null) return fallback;
            log.info("SEQUENCE_FALLBACK_FAILED senderId={} entities={}", senderId, candidate.getEntityNamesInOrder());
            return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);
        }

        LearnedTemplate winner = validated.size() == 1
                ? validated.get(0)
                : resolveMultipleMatches(validated).orElse(null);

        if (winner == null) {
            log.info("AMBIGUOUS_TEMPLATE_CONFLICT senderId={} entities={} validatedCount={}",
                    senderId, candidate.getEntityNamesInOrder(), validated.size());
            return CandidateOutcome.failed(LlmReason.AMBIGUOUS_TEMPLATE_CONFLICT);
        }

        return CandidateOutcome.matched(new TemplateMatchResult(winner, candidate));
    }

    private LlmReason moreSpecific(LlmReason current, LlmReason candidate) {
        return priority(candidate) > priority(current) ? candidate : current;
    }

    private int priority(LlmReason reason) {
        return switch (reason) {
            case AMBIGUOUS_TEMPLATE_CONFLICT          -> 4;
            case BOUNDARY_VALIDATION_FAILED           -> 3;
            case LOW_CONFIDENCE_TEMPLATE_INACTIVE     -> 2;
            case NO_TEMPLATE_MATCH_BOTH_HASHES_MISSED -> 1;
            default                                   -> 0;
        };
    }


    private boolean boundaryValidationPasses(String normalizedSms, LearnedTemplate template, CandidateTemplate candidate) {
        for (EntitySnapshot snapshot : template.getEntitySnapshots()) {
            // Find ALL extracted values with this entity name — handles duplicate entity names (e.g. AMOUNT twice)
            List<ExtractedValue> allValues = findAllExtractedValues(candidate, snapshot.getName());
            if (allValues.isEmpty()) {
                log.info("BOUNDARY_FAIL templateId={} entity={} reason=NOT_IN_CANDIDATE",
                        template.getTemplateId(), snapshot.getName());
                return false;
            }

            // At least ONE of the values must satisfy this snapshot's boundary conditions
            boolean anyMatch = allValues.stream().anyMatch(ev ->
                    satisfiesBoundaryConditions(normalizedSms, snapshot, ev, candidate, template.getTemplateId()));
            if (!anyMatch) {
                return false;
            }
        }
        return true;
    }

    private boolean containsToken(String text, String token) {
        String upperText = text.toUpperCase();
        String upperToken = token.toUpperCase();
        if (upperToken.chars().noneMatch(Character::isLetterOrDigit)) {
            return upperText.contains(upperToken);
        }
        int idx = upperText.indexOf(upperToken);
        while (idx >= 0) {
            boolean startOk = !Character.isLetterOrDigit(upperToken.charAt(0)) ||
                              (idx == 0) || !Character.isLetterOrDigit(upperText.charAt(idx - 1));
            int endIdx = idx + upperToken.length();
            boolean endOk = !Character.isLetterOrDigit(upperToken.charAt(upperToken.length() - 1)) ||
                            (endIdx >= upperText.length()) || !Character.isLetter(upperText.charAt(endIdx));
            if (startOk && endOk) return true;
            idx = upperText.indexOf(upperToken, idx + 1);
        }
        return false;
    }

    private boolean hasLetterOrDigit(String s) {
        return s.chars().anyMatch(Character::isLetterOrDigit);
    }

    private boolean isEntityRef(String boundary) {
        return boundary != null && boundary.length() > 2 && boundary.startsWith("{") && boundary.endsWith("}");
    }

    private boolean isSom(String boundary) { return "SOM".equalsIgnoreCase(boundary); }
    private boolean isEom(String boundary) { return "EOM".equalsIgnoreCase(boundary); }

    private String resolveEntityName(String boundary) {
        return boundary.substring(1, boundary.length() - 1);
    }

    private ExtractedValue findExtractedValue(CandidateTemplate candidate, String entityName) {
        for (ExtractedValue ev : candidate.getExtractedValues()) {
            if (Objects.equals(ev.getEntityName(), entityName)) return ev;
        }
        return null;
    }

    private List<ExtractedValue> findAllExtractedValues(CandidateTemplate candidate, String entityName) {
        List<ExtractedValue> result = new ArrayList<>();
        for (ExtractedValue ev : candidate.getExtractedValues()) {
            if (Objects.equals(ev.getEntityName(), entityName)) result.add(ev);
        }
        return result;
    }

    private boolean satisfiesBoundaryConditions(String normalizedSms, EntitySnapshot snapshot,
                                                 ExtractedValue ev, CandidateTemplate candidate,
                                                 String templateId) {
        String startAfter = snapshot.getStartAfter();
        String endBefore = snapshot.getEndBefore();

        if (startAfter != null && !startAfter.isEmpty() && !isSom(startAfter)) {
            if (isEntityRef(startAfter)) {
                ExtractedValue ref = findExtractedValue(candidate, resolveEntityName(startAfter));
                if (ref == null || ref.getEndPosition() > ev.getStartPosition()) {
                    log.info("BOUNDARY_FAIL templateId={} entity={} startAfter='{}' reason=ENTITY_REF_NOT_BEFORE value='{}' pos={}",
                            templateId, snapshot.getName(), startAfter, ev.getValue(), ev.getStartPosition());
                    return false;
                }
            } else {
                // The last token of startAfter must be the last token immediately before the entity
                String textBefore = normalizedSms.substring(0, ev.getStartPosition());
                String upperText = textBefore.toUpperCase();
                String upperToken = startAfter.toUpperCase();
                int lastIdx = upperText.lastIndexOf(upperToken);
                if (lastIdx < 0) {
                    log.info("BOUNDARY_FAIL templateId={} entity={} startAfter='{}' reason=TOKEN_NOT_FOUND_BEFORE value='{}'",
                            templateId, snapshot.getName(), startAfter, ev.getValue());
                    return false;
                }
                if (!textBefore.substring(lastIdx + startAfter.length()).trim().isEmpty()) {
                    log.info("BOUNDARY_FAIL templateId={} entity={} startAfter='{}' reason=NOT_LAST_TOKEN_BEFORE value='{}' between='{}'",
                            templateId, snapshot.getName(), startAfter, ev.getValue(),
                            textBefore.substring(lastIdx + startAfter.length()).trim());
                    return false;
                }
            }
        }

        if (endBefore != null && !endBefore.isEmpty() && !isEom(endBefore)) {
            if (isEntityRef(endBefore)) {
                ExtractedValue ref = findExtractedValue(candidate, resolveEntityName(endBefore));
                if (ref == null || ref.getStartPosition() < ev.getEndPosition()) {
                    log.info("BOUNDARY_FAIL templateId={} entity={} endBefore='{}' reason=ENTITY_REF_NOT_AFTER value='{}' pos={}",
                            templateId, snapshot.getName(), endBefore, ev.getValue(), ev.getEndPosition());
                    return false;
                }
            } else if (hasLetterOrDigit(endBefore)) {
                // endBefore must be the first meaningful token after the entity value
                String textAfter = normalizedSms.substring(ev.getEndPosition());
                int idx = textAfter.toUpperCase().indexOf(endBefore.toUpperCase());
                if (idx < 0) {
                    log.info("BOUNDARY_FAIL templateId={} entity={} endBefore='{}' reason=TOKEN_NOT_FOUND_AFTER value='{}'",
                            templateId, snapshot.getName(), endBefore, ev.getValue());
                    return false;
                }
                if (!textAfter.substring(0, idx).trim().isEmpty()) {
                    log.info("BOUNDARY_FAIL templateId={} entity={} endBefore='{}' reason=NOT_FIRST_TOKEN_AFTER value='{}' between='{}'",
                            templateId, snapshot.getName(), endBefore, ev.getValue(), textAfter.substring(0, idx).trim());
                    return false;
                }
            }
        }
        return true;
    }

    private Optional<LearnedTemplate> resolveMultipleMatches(List<LearnedTemplate> templates) {
        LearnedTemplate first = templates.get(0);
        for (LearnedTemplate t : templates) {
            if (!Objects.equals(t.getCategory(), first.getCategory())
                    || !Objects.equals(t.getSubcategory(), first.getSubcategory())) {
                log.debug("Multiple templates with conflicting category/subcategory — fallback to LLM");
                return Optional.empty();
            }
        }
        // category and subcategory agree — pick first (intent may vary due to LLM inconsistency)
        return Optional.of(first);
    }

    private CandidateOutcome trySequenceFallback(String normalizedSms, List<LearnedTemplate> seqMatches,
                                                   CandidateTemplate originalCandidate) {
        for (LearnedTemplate template : seqMatches) {
            if (!template.isActive()) continue;
            CandidateOutcome outcome = sequenceAndWordCountCheck(normalizedSms, template, originalCandidate);
            if (outcome.result != null) {
                log.info("SEQUENCE_FALLBACK_HIT templateId={}", template.getTemplateId());
                return outcome;
            }
        }
        return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);
    }

    private CandidateOutcome sequenceAndWordCountCheck(String normalizedSms, LearnedTemplate template,
                                                        CandidateTemplate originalCandidate) {
        List<ExtractedValue> fallbackValues = new ArrayList<>();
        Map<String, Integer> occurrenceIndex = new HashMap<>();
        int pos = 0;

        for (EntitySnapshot snapshot : template.getEntitySnapshots()) {
            String startAfter = snapshot.getStartAfter();
            String endBefore  = snapshot.getEndBefore();

            boolean skipStart = startAfter == null || startAfter.isEmpty() || isSom(startAfter) || isEntityRef(startAfter);
            boolean skipEnd   = endBefore  == null || endBefore.isEmpty()  || isEom(endBefore)  || isEntityRef(endBefore);

            int valueStart = pos;

            // Advance past startAfter
            if (!skipStart) {
                int idx = findTokenInText(normalizedSms, startAfter, pos);
                if (idx < 0) {
                    log.info("SEQUENCE_FALLBACK_FAIL templateId={} entity={} startAfter='{}' not found from pos={}",
                            template.getTemplateId(), snapshot.getName(), startAfter, pos);
                    return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);
                }
                valueStart = idx + startAfter.length();
                if (valueStart < normalizedSms.length() && normalizedSms.charAt(valueStart) == ' ') valueStart++;
                pos = valueStart;
            }

            int valueEnd;

            if (!skipEnd) {
                int idx = findTokenInText(normalizedSms, endBefore, valueStart);
                if (idx < 0) {
                    log.info("SEQUENCE_FALLBACK_FAIL templateId={} entity={} endBefore='{}' not found from pos={}",
                            template.getTemplateId(), snapshot.getName(), endBefore, valueStart);
                    return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);
                }
                valueEnd = idx;
                pos = idx + endBefore.length(); // advance past endBefore
            } else if (snapshot.getMaxTokens() > 0) {
                valueEnd = greedyEndIndex(normalizedSms, valueStart, snapshot.getMaxTokens());
                if (valueEnd < 0) return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);
                pos = valueEnd;
            } else {
                // REGEX entity with no endBefore — use original candidate value, just advance pos
                int occIdx = occurrenceIndex.getOrDefault(snapshot.getName(), 0);
                List<ExtractedValue> origValues = findAllExtractedValues(originalCandidate, snapshot.getName());
                if (occIdx < origValues.size()) {
                    fallbackValues.add(origValues.get(occIdx));
                    pos = origValues.get(occIdx).getEndPosition();
                }
                occurrenceIndex.put(snapshot.getName(), occIdx + 1);
                continue;
            }

            String extracted = normalizedSms.substring(valueStart, valueEnd).trim();
            if (extracted.isEmpty()) {
                log.info("SEQUENCE_FALLBACK_FAIL templateId={} entity={} extracted empty",
                        template.getTemplateId(), snapshot.getName());
                return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);
            }

            // Word count check for BOUNDARY_HINT entities
            if (snapshot.getType() == ExtractionRuleType.BOUNDARY_HINT && snapshot.getMaxTokens() > 0) {
                int wordCount = extracted.split("\\s+").length;
                if (wordCount > snapshot.getMaxTokens()) {
                    log.info("SEQUENCE_FALLBACK_FAIL templateId={} entity={} wordCount={} > maxTokens={}",
                            template.getTemplateId(), snapshot.getName(), wordCount, snapshot.getMaxTokens());
                    return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);
                }
            }

            fallbackValues.add(ExtractedValue.builder()
                    .entityName(snapshot.getName())
                    .value(extracted)
                    .startPosition(valueStart)
                    .endPosition(valueEnd)
                    .build());
        }

        if (fallbackValues.isEmpty()) return CandidateOutcome.failed(LlmReason.BOUNDARY_VALIDATION_FAILED);

        List<String> namesInOrder = fallbackValues.stream()
                .map(ExtractedValue::getEntityName)
                .collect(Collectors.toList());

        CandidateTemplate fallbackCandidate = CandidateTemplate.builder()
                .extractedValues(fallbackValues)
                .entityNamesInOrder(namesInOrder)
                .build();

        return CandidateOutcome.matched(new TemplateMatchResult(template, fallbackCandidate));
    }

    private int findTokenInText(String text, String token, int fromIndex) {
        String upperToken = token.toUpperCase();
        int idx = text.toUpperCase().indexOf(upperToken, fromIndex);
        while (idx >= 0) {
            boolean startOk = !Character.isLetterOrDigit(upperToken.charAt(0)) ||
                              (idx == 0) || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int endIdx = idx + upperToken.length();
            boolean endOk = !Character.isLetterOrDigit(upperToken.charAt(upperToken.length() - 1)) ||
                            (endIdx >= text.length()) || !Character.isLetter(text.charAt(endIdx));
            if (startOk && endOk) return idx;
            idx = text.toUpperCase().indexOf(upperToken, idx + 1);
        }
        return -1;
    }

    private int greedyEndIndex(String text, int valueStart, int maxTokens) {
        int wordCount = 0;
        int i = valueStart;
        while (i < text.length() && wordCount < maxTokens) {
            while (i < text.length() && text.charAt(i) == ' ') i++;
            if (i >= text.length()) break;
            while (i < text.length() && text.charAt(i) != ' ') i++;
            wordCount++;
        }
        return wordCount > 0 ? i : -1;
    }

    private static class CandidateOutcome {
        final TemplateMatchResult result;
        final LlmReason failReason;

        private CandidateOutcome(TemplateMatchResult result, LlmReason failReason) {
            this.result = result;
            this.failReason = failReason;
        }

        static CandidateOutcome matched(TemplateMatchResult r) { return new CandidateOutcome(r, null); }
        static CandidateOutcome failed(LlmReason r)            { return new CandidateOutcome(null, r); }
    }
}
