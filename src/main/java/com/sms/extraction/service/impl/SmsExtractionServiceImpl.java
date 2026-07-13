package com.sms.extraction.service.impl;

import com.sms.extraction.domain.BoundaryPair;
import com.sms.extraction.domain.RegexVariant;
import com.sms.extraction.domain.CandidateTemplate;
import com.sms.extraction.domain.EntitySnapshot;
import com.sms.extraction.domain.ExtractionResult;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.ExtractedValue;
import com.sms.extraction.domain.GlobalEntity;
import com.sms.extraction.domain.LearnedTemplate;
import com.sms.extraction.domain.LlmEntityInfo;
import com.sms.extraction.domain.LlmReason;
import com.sms.extraction.domain.LlmResponse;
import com.sms.extraction.domain.TemplateMatchOutcome;
import com.sms.extraction.domain.TemplateMatchResult;
import com.sms.extraction.llm.LlmClient;
import com.sms.extraction.metrics.ExtractionMetrics;
import com.sms.extraction.metrics.MessageRecord;
import com.sms.extraction.repository.EntityRepository;
import com.sms.extraction.repository.ExtractionResultRepository;
import com.sms.extraction.repository.TemplateRepository;
import com.sms.extraction.service.EntityExtractionService;
import com.sms.extraction.service.NormalizationService;
import com.sms.extraction.service.SenderLearningStateService;
import com.sms.extraction.service.SmsExtractionService;
import com.sms.extraction.service.TemplateMatchingService;
import com.sms.extraction.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class SmsExtractionServiceImpl implements SmsExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SmsExtractionServiceImpl.class);

    private final NormalizationService normalizationService;
    private final EntityExtractionService entityExtractionService;
    private final TemplateMatchingService templateMatchingService;
    private final LlmClient llmClient;
    private final TemplateRepository templateRepository;
    private final EntityRepository entityRepository;
    private final ExtractionResultRepository extractionResultRepository;
    private final ExtractionMetrics metrics;
    private final double confidenceThreshold;
    private final SenderLearningStateService learningStateService;
    private final Semaphore llmSemaphore;

    public SmsExtractionServiceImpl(NormalizationService normalizationService,
                                     EntityExtractionService entityExtractionService,
                                     TemplateMatchingService templateMatchingService,
                                     LlmClient llmClient,
                                     TemplateRepository templateRepository,
                                     EntityRepository entityRepository,
                                     ExtractionResultRepository extractionResultRepository,
                                     ExtractionMetrics metrics,
                                     @Value("${template.confidence-threshold:0.50}") double confidenceThreshold,
                                     SenderLearningStateService learningStateService,
                                     @Value("${llm.max-concurrent-calls:20}") int maxConcurrentLlmCalls) {
        this.normalizationService = normalizationService;
        this.entityExtractionService = entityExtractionService;
        this.templateMatchingService = templateMatchingService;
        this.llmClient = llmClient;
        this.templateRepository = templateRepository;
        this.entityRepository = entityRepository;
        this.extractionResultRepository = extractionResultRepository;
        this.metrics = metrics;
        this.confidenceThreshold = confidenceThreshold;
        this.learningStateService = learningStateService;
        this.llmSemaphore = new Semaphore(maxConcurrentLlmCalls);
    }

    private boolean isNonEnglish(String text) {
        long letters = text.chars().filter(Character::isLetter).count();
        if (letters == 0) return false;
        long nonAscii = text.chars().filter(c -> c > 127).count();
        return (double) nonAscii / letters > 0.3;
    }

    @Override
    public ExtractionResult process(String senderId, String rawSms) {
        long startMs = System.currentTimeMillis();

        if (isNonEnglish(rawSms)) {
            log.info("SKIPPED non-English message senderId={} raw='{}'", senderId, rawSms.substring(0, Math.min(60, rawSms.length())));
            return ExtractionResult.builder()
                    .messageId(java.util.UUID.randomUUID().toString())
                    .senderId(senderId)
                    .rawSms(rawSms)
                    .timestamp(java.time.Instant.now())
                    .build();
        }

        String messageTraceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.info("━━━ START senderId={} traceId={} raw='{}'", senderId, messageTraceId, rawSms);
        metrics.incrementTotalMessages();
        log.info("TRACE traceId={} step=PRE_NORMALIZE senderId={}", messageTraceId, senderId);

        String normalizedSms = normalizationService.normalize(rawSms);
        log.info("TRACE traceId={} step=POST_NORMALIZE senderId={}", messageTraceId, senderId);
        log.info("  [1] NORMALIZED: '{}'", normalizedSms);
        log.info("TRACE traceId={} step=POST_LOG_NORMALIZED senderId={}", messageTraceId, senderId);

        List<GlobalEntity> globalEntities = entityRepository.findBySenderId(senderId);
        log.info("TRACE traceId={} step=POST_ENTITY_LOAD count={} senderId={}", messageTraceId, globalEntities.size(), senderId);
        log.info("  [2] GLOBAL ENTITIES LOADED: count={} names={}",
                globalEntities.size(),
                globalEntities.stream().map(GlobalEntity::getName).toList());

        log.info("TRACE traceId={} step=PRE_EXTRACT senderId={}", messageTraceId, senderId);
        List<CandidateTemplate> candidates = entityExtractionService.extract(normalizedSms, globalEntities);
        log.info("TRACE traceId={} step=POST_EXTRACT candidateCount={} senderId={}", messageTraceId, candidates.size(), senderId);
        log.info("  [3] EXTRACTION: candidates={}", candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            log.info("      candidate[{}] entities={}", i,
                    candidates.get(i).getExtractedValues().stream()
                            .map(v -> v.getEntityName() + "='" + v.getValue() + "'@[" + v.getStartPosition() + "-" + v.getEndPosition() + "]")
                            .toList());
        }
        if (candidates.isEmpty()) {
            if (globalEntities.isEmpty()) {
                log.info("  [3] NO EXTRACTION CANDIDATES — new sender, entity list is empty");
            } else {
                log.warn("  [3] EXTRACTION_CANDIDATES_DISCARDED — sender has {} entities but extraction produced no valid candidates (overlap/regex bug)",
                        globalEntities.size());
            }
        }

        Long templateMatchLatencyMs = null;
        TemplateMatchOutcome matchOutcome;

        log.info("TRACE traceId={} step=PRE_TEMPLATE_MATCH candidateCount={} senderId={}", messageTraceId, candidates.size(), senderId);
        long matchStart = System.currentTimeMillis();
        matchOutcome = templateMatchingService.match(normalizedSms, candidates, senderId);
        templateMatchLatencyMs = System.currentTimeMillis() - matchStart;
        log.info("TRACE traceId={} step=POST_TEMPLATE_MATCH matched={} senderId={}", messageTraceId, matchOutcome.isMatched(), senderId);
        log.info("  [4] TEMPLATE MATCH: matched={} reason={} candidateCount={} latencyMs={}",
                matchOutcome.isMatched(),
                matchOutcome.isMatched() ? "HIT" : matchOutcome.getFailReason(),
                candidates.size(),
                templateMatchLatencyMs);

        if (!matchOutcome.isMatched() && candidates.isEmpty()) {
            if (!globalEntities.isEmpty()) {
                matchOutcome = TemplateMatchOutcome.failed(LlmReason.EXTRACTION_CANDIDATES_DISCARDED);
                log.warn("  [4] ALL_CANDIDATES_DISCARDED senderId={} entityCount={} — all generated candidates had overlapping entities",
                        senderId, globalEntities.size());
            }
        }

        ExtractionResult result;
        Long llmLatencyMs = null;
        String path;

        if (matchOutcome.isMatched()) {
            TemplateMatchResult match = matchOutcome.getMatchResult().get();
            log.info("  [5] TEMPLATE HIT templateId={} category={} subcategory={} intent={} winningCandidate={}",
                    match.getTemplate().getTemplateId(),
                    match.getTemplate().getCategory(),
                    match.getTemplate().getSubcategory(),
                    match.getTemplate().getIntent(),
                    match.getWinningCandidate().getEntityNamesInOrder());
            metrics.incrementTemplateHits();
            metrics.recordTemplateHitLatency(templateMatchLatencyMs);
            path = "TEMPLATE_HIT";

            Map<String, String> extractedEntities = buildExtractedEntitiesWithSemanticTypes(
                    match.getWinningCandidate(), match.getTemplate().getEntitySnapshots(), normalizedSms);
            result = buildResult(senderId, rawSms, normalizedSms,
                    match.getTemplate().getTemplateId(), match.getTemplate().getCategory(),
                    match.getTemplate().getSubcategory(), match.getTemplate().getIntent(),
                    match.getTemplate().getConfidenceScore(), extractedEntities);
        } else {
            LlmReason llmReason = matchOutcome.getFailReason();
            log.warn("  [5] LLM PATH reason={} senderId={} candidateCount={} candidates={}",
                    llmReason, senderId, candidates.size(),
                    candidates.stream().map(c -> c.getEntityNamesInOrder().toString()).toList());

            // Atomically try to claim LEARNING_IN_PROGRESS via setIfAbsent — eliminates race condition
            boolean claimed = learningStateService.tryClaimLearning(senderId);

            // Wait-retry loop: threads that lost the claim wait, retry, and only ONE proceeds to LLM
            while (!claimed) {
                log.info("TRACE traceId={} step=ENTERING_WAIT senderId={}", messageTraceId, senderId);
                log.info("  [5] WAITING — senderId={} already LEARNING_IN_PROGRESS, subscribing to completion topic", senderId);
                learningStateService.waitForCompletion(senderId);
                log.info("TRACE traceId={} step=WOKE_FROM_WAIT senderId={}", messageTraceId, senderId);

                // All threads wake up simultaneously — each retries extraction + template match
                ExtractionResult retryResult = retryAfterLearningComplete(senderId, rawSms, normalizedSms);
                if (retryResult != null) {
                    log.info("METRICS_RECORDED traceId={} path=RETRY_HIT senderId={}", messageTraceId, senderId);
                    long totalMs = System.currentTimeMillis() - startMs;
                    metrics.recordMessage(MessageRecord.builder()
                            .messageId(retryResult.getMessageId())
                            .senderId(senderId)
                            .rawSms(rawSms)
                            .normalizedSms(normalizedSms)
                            .path("TEMPLATE_HIT")
                            .llmReason(null)
                            .templateId(retryResult.getTemplateId())
                            .category(retryResult.getCategory())
                            .subcategory(retryResult.getSubcategory())
                            .intent(retryResult.getIntent())
                            .extractedEntities(retryResult.getExtractedEntities())
                            .confidenceScore(retryResult.getConfidenceScore())
                            .totalLatencyMs(totalMs)
                            .llmLatencyMs(null)
                            .templateMatchLatencyMs(null)
                            .timestamp(retryResult.getTimestamp().toString())
                            .build());
                    saveAndIndex(retryResult);
                    return retryResult;
                }

                // Retry missed — only ONE thread claims the next LLM call, rest go back to waiting
                log.info("  [5] RETRY MISS — senderId={} competing for next LLM slot", senderId);
                claimed = learningStateService.tryClaimLearning(senderId);
                // claimed=false → loop back to waitForCompletion (another thread won the slot)
                // claimed=true  → exit loop, proceed to LLM below
            }

            // One final check before calling LLM — previous learner may have just saved the template
            ExtractionResult preCheckResult = retryAfterLearningComplete(senderId, rawSms, normalizedSms);
            if (preCheckResult != null) {
                log.info("  [5] PRE-LLM HIT — template found with fresh entities, skipping LLM for senderId={}", senderId);
                log.info("METRICS_RECORDED traceId={} path=PRE_LLM_HIT senderId={}", messageTraceId, senderId);
                learningStateService.setState(senderId, SenderLearningStateService.LearningState.LEARNED);
                learningStateService.publishCompletion(senderId);
                long totalMs = System.currentTimeMillis() - startMs;
                metrics.recordMessage(MessageRecord.builder()
                        .messageId(preCheckResult.getMessageId()).senderId(senderId)
                        .rawSms(rawSms).normalizedSms(normalizedSms).path("TEMPLATE_HIT")
                        .llmReason(null).templateId(preCheckResult.getTemplateId())
                        .category(preCheckResult.getCategory()).subcategory(preCheckResult.getSubcategory())
                        .intent(preCheckResult.getIntent()).extractedEntities(preCheckResult.getExtractedEntities())
                        .confidenceScore(preCheckResult.getConfidenceScore()).totalLatencyMs(totalMs)
                        .llmLatencyMs(null).templateMatchLatencyMs(null)
                        .timestamp(preCheckResult.getTimestamp().toString()).build());
                saveAndIndex(preCheckResult);
                return preCheckResult;
            }

            metrics.incrementLlmCalls();
            metrics.incrementLlmReason(llmReason);
            path = "LLM";

            // tryClaimLearning already set LEARNING_IN_PROGRESS atomically — no separate lock needed.
            // Re-fetch entities — previous learner may have added new variants
            List<GlobalEntity> freshEntities = entityRepository.findBySenderId(senderId);
            List<GlobalEntity> entitiesForLlm = freshEntities.isEmpty() ? globalEntities : freshEntities;
            log.info("  [5] LLM entities refreshed: {} → {} entities for senderId={}",
                    globalEntities.size(), entitiesForLlm.size(), senderId);

            long llmStart = System.currentTimeMillis();
            LlmResponse llmResponse;
            try {
                llmSemaphore.acquire();
                try {
                    llmResponse = llmClient.call(normalizedSms, entitiesForLlm);
                } finally {
                    llmSemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                learningStateService.setState(senderId, SenderLearningStateService.LearningState.LEARNING_FAILED);
                learningStateService.publishCompletion(senderId);
                throw new RuntimeException("LLM call interrupted for senderId=" + senderId, e);
            } catch (Exception e) {
                metrics.incrementLlmCallsFailed();
                log.error("  [5] LLM FAILED senderId={}", senderId, e);
                learningStateService.setState(senderId, SenderLearningStateService.LearningState.LEARNING_FAILED);
                learningStateService.publishCompletion(senderId);
                throw e;
            }
            llmLatencyMs = System.currentTimeMillis() - llmStart;
            metrics.recordLlmLatency(llmLatencyMs);
            metrics.addTokenUsage(llmResponse.getInputTokens(), llmResponse.getOutputTokens(),
                    llmResponse.getCachedTokens(), llmResponse.getReasoningTokens());
            log.info("  [5] LLM RESPONSE latencyMs={} canonical='{}' entities={} ordering={}",
                    llmLatencyMs, llmResponse.getCanonicalTemplate(),
                    llmResponse.getEntities().stream().map(e -> e.getName() + "='" + llmResponse.getExtractedFields().get(e.getName()) + "'").toList(),
                    llmResponse.getOrdering());

            String canonicalTemplate = llmResponse.getCanonicalTemplate();
            String templateId = HashUtil.sha256(senderId + "::" + canonicalTemplate);

            List<ExtractedValue> llmExtractedValues = buildExtractedValuesFromLlm(llmResponse, normalizedSms);
            String staticHash = HashUtil.staticTextHash(normalizedSms, llmExtractedValues);
            String seqHash = HashUtil.placeholderSequenceHash(llmResponse.getOrdering());
            List<EntitySnapshot> snapshots = buildEntitySnapshots(llmResponse);

            log.info("  [6] TEMPLATE ID={} staticHash={} seqHash={}", templateId, staticHash, seqHash);

            boolean isRedundant = templateRepository.findById(templateId).isPresent();
            boolean active = llmResponse.getConfidenceScore() >= confidenceThreshold;

            if (isRedundant) {
                metrics.incrementRedundantLlmCalls();
                log.warn("  [6] REDUNDANT LLM — templateId={} already exists. LLM was called unnecessarily for senderId={}. reason={}",
                        templateId, senderId, llmReason);
            } else if (active) {
                metrics.incrementTemplatesLearned();
            } else {
                metrics.incrementTemplatesSkippedLowConfidence();
            }

            LearnedTemplate learnedTemplate = LearnedTemplate.builder()
                    .templateId(templateId)
                    .senderId(senderId)
                    .category(llmResponse.getCategory())
                    .subcategory(llmResponse.getSubcategory())
                    .intent(llmResponse.getIntent())
                    .confidenceScore(llmResponse.getConfidenceScore())
                    .canonicalTemplate(canonicalTemplate)
                    .entitySnapshots(snapshots)
                    .ordering(llmResponse.getOrdering())
                    .staticTextHash(staticHash)
                    .placeholderSequenceHash(seqHash)
                    .active(active)
                    .build();

            templateRepository.save(learnedTemplate);
            updateGlobalEntities(senderId, globalEntities, llmResponse);

            // Build extractedEntities map using semanticType as key
            Map<String, String> extractedEntities = new HashMap<>();
            for (LlmEntityInfo entityInfo : llmResponse.getEntities()) {
                String value = llmResponse.getExtractedFields().get(entityInfo.getName());
                if (value == null) value = llmResponse.getExtractedFields().get(entityInfo.getSemanticType());
                if (value != null) extractedEntities.put(entityInfo.getSemanticType(), value);
            }
            result = buildResult(senderId, rawSms, normalizedSms, templateId,
                    llmResponse.getCategory(), llmResponse.getSubcategory(), llmResponse.getIntent(),
                    llmResponse.getConfidenceScore(), extractedEntities);

            // Set state then publish — waiting threads will wake up and retry
            learningStateService.setState(senderId,
                    active ? SenderLearningStateService.LearningState.LEARNED
                           : SenderLearningStateService.LearningState.LEARNING_FAILED);
            learningStateService.publishCompletion(senderId);

            // Push per-message record
            long totalMs = System.currentTimeMillis() - startMs;
            metrics.recordMessage(MessageRecord.builder()
                    .messageId(result.getMessageId())
                    .senderId(senderId)
                    .rawSms(rawSms)
                    .normalizedSms(normalizedSms)
                    .path(path)
                    .llmReason(llmReason)
                    .templateId(result.getTemplateId())
                    .category(result.getCategory())
                    .subcategory(result.getSubcategory())
                    .intent(result.getIntent())
                    .extractedEntities(result.getExtractedEntities())
                    .confidenceScore(result.getConfidenceScore())
                    .totalLatencyMs(totalMs)
                    .llmLatencyMs(llmLatencyMs)
                    .templateMatchLatencyMs(null)
                    .timestamp(result.getTimestamp().toString())
                    .build());

            log.info("  [7] SAVED templateId={} active={}", templateId, active);
            log.info("━━━ END senderId={} path=LLM reason={} totalMs={} llmMs={}",
                    senderId, llmReason, System.currentTimeMillis() - startMs, llmLatencyMs);
            log.info("METRICS_RECORDED traceId={} path=LLM senderId={}", messageTraceId, senderId);
            saveAndIndex(result);
            return result;
        }

        long totalMs = System.currentTimeMillis() - startMs;
        log.info("━━━ END senderId={} path=TEMPLATE_HIT templateId={} totalMs={} matchMs={}",
                senderId, result.getTemplateId(), totalMs, templateMatchLatencyMs);
        log.info("METRICS_RECORDED traceId={} path=TEMPLATE_HIT senderId={}", messageTraceId, senderId);
        metrics.recordMessage(MessageRecord.builder()
                .messageId(result.getMessageId())
                .senderId(senderId)
                .rawSms(rawSms)
                .normalizedSms(normalizedSms)
                .path(path)
                .llmReason(null)
                .templateId(result.getTemplateId())
                .category(result.getCategory())
                .subcategory(result.getSubcategory())
                .intent(result.getIntent())
                .extractedEntities(result.getExtractedEntities())
                .confidenceScore(result.getConfidenceScore())
                .totalLatencyMs(totalMs)
                .llmLatencyMs(null)
                .templateMatchLatencyMs(templateMatchLatencyMs)
                .timestamp(result.getTimestamp().toString())
                .build());

        saveAndIndex(result);
        return result;
    }

private ExtractionResult retryAfterLearningComplete(String senderId, String rawSms, String normalizedSms) {
        List<GlobalEntity> refreshedEntities = entityRepository.findBySenderId(senderId);
        log.info("  [RETRY] Reloaded {} entities for senderId={} after wait", refreshedEntities.size(), senderId);

        List<CandidateTemplate> retryCandidates = entityExtractionService.extract(normalizedSms, refreshedEntities);

        TemplateMatchOutcome retryOutcome = templateMatchingService.match(normalizedSms, retryCandidates, senderId);
        if (!retryOutcome.isMatched()) return null;

        TemplateMatchResult match = retryOutcome.getMatchResult().get();
        log.info("  [RETRY] TEMPLATE HIT after wait templateId={}", match.getTemplate().getTemplateId());
        metrics.incrementTemplateHits();
        Map<String, String> extractedEntities = buildExtractedEntitiesWithSemanticTypes(
                match.getWinningCandidate(), match.getTemplate().getEntitySnapshots(), normalizedSms);
        return buildResult(senderId, rawSms, normalizedSms,
                match.getTemplate().getTemplateId(), match.getTemplate().getCategory(),
                match.getTemplate().getSubcategory(), match.getTemplate().getIntent(),
                match.getTemplate().getConfidenceScore(), extractedEntities);
    }

    private void saveAndIndex(ExtractionResult result) {
        extractionResultRepository.save(result);
    }

    private ExtractionResult buildResult(String senderId, String rawSms, String normalizedSms,
                                          String templateId, String category, String subcategory,
                                          String intent, double confidenceScore,
                                          Map<String, String> extractedEntities) {
        return ExtractionResult.builder()
                .messageId(UUID.randomUUID().toString())
                .senderId(senderId)
                .rawSms(rawSms)
                .normalizedSms(normalizedSms)
                .templateId(templateId)
                .category(category)
                .subcategory(subcategory)
                .intent(intent)
                .confidenceScore(confidenceScore)
                .extractedEntities(extractedEntities)
                .timestamp(Instant.now())
                .build();
    }

    private Map<String, String> buildExtractedEntitiesFromCandidate(CandidateTemplate candidate) {
        Map<String, String> entities = new HashMap<>();
        for (ExtractedValue ev : candidate.getExtractedValues()) {
            entities.put(ev.getEntityName(), ev.getValue());
        }
        return entities;
    }

private Map<String, String> buildExtractedEntitiesWithSemanticTypes(
            CandidateTemplate candidate, List<EntitySnapshot> snapshots, String normalizedSms) {
        Map<String, String> entities = new HashMap<>();
        for (ExtractedValue ev : candidate.getExtractedValues()) {
            // Find the snapshot for this entity that best matches by boundary conditions
            String semanticType = ev.getEntityName(); // default fallback
            for (EntitySnapshot snapshot : snapshots) {
                if (!snapshot.getName().equals(ev.getEntityName())) continue;
                String textBefore = normalizedSms.substring(0, ev.getStartPosition());
                String startAfter = snapshot.getStartAfter();
                if (startAfter != null && !startAfter.isEmpty() && !startAfter.startsWith("{")) {
                    if (textBefore.toUpperCase().contains(startAfter.toUpperCase())
                            && !entities.containsKey(snapshot.getSemanticType())) {
                        semanticType = snapshot.getSemanticType();
                        break;
                    }
                } else {
                    if (!entities.containsKey(snapshot.getSemanticType())) {
                        semanticType = snapshot.getSemanticType();
                        break;
                    }
                }
            }
            entities.put(semanticType, ev.getValue());
        }
        return entities;
    }

    private List<ExtractedValue> buildExtractedValuesFromLlm(LlmResponse llmResponse, String normalizedSms) {
        List<ExtractedValue> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : llmResponse.getExtractedFields().entrySet()) {
            String entityName = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.equals("null") || value.isBlank()) continue;
            int idx = normalizedSms.indexOf(value);
            int start = idx >= 0 ? idx : 0;
            int end   = idx >= 0 ? idx + value.length() : 0;
            values.add(ExtractedValue.builder()
                    .entityName(entityName)
                    .value(value)
                    .startPosition(start)
                    .endPosition(end)
                    .build());
        }
        return values;
    }

    private List<EntitySnapshot> buildEntitySnapshots(LlmResponse llmResponse) {
        List<EntitySnapshot> snapshots = new ArrayList<>();
        for (LlmEntityInfo entityInfo : llmResponse.getEntities()) {
            snapshots.add(EntitySnapshot.builder()
                    .name(entityInfo.getName())
                    .semanticType(entityInfo.getSemanticType())
                    .type(entityInfo.getType())
                    .regex(entityInfo.getRegex())
                    .group(entityInfo.getGroup())
                    .startAfter(entityInfo.getStartAfter())
                    .endBefore(entityInfo.getEndBefore())
                    .maxTokens(entityInfo.getMaxTokens())
                    .build());
        }
        return snapshots;
    }

    private void updateGlobalEntities(String senderId, List<GlobalEntity> existing, LlmResponse llmResponse) {
        // Re-read from DynamoDB inside the lock to pick up any additions from concurrent LLM calls
        List<GlobalEntity> latest = entityRepository.findBySenderId(senderId);
        List<GlobalEntity> base = latest.isEmpty() ? existing : latest;
        Map<String, GlobalEntity> entityMap = new HashMap<>();
        for (GlobalEntity ge : base) entityMap.put(ge.getName(), ge);

        for (LlmEntityInfo info : llmResponse.getEntities()) {
            if (entityMap.containsKey(info.getName())) {
                GlobalEntity existingEntity = entityMap.get(info.getName());

                // Append new boundary pair if not duplicate
                List<BoundaryPair> updatedPairs = new ArrayList<>(existingEntity.getBoundaryPairs());
                BoundaryPair newPair = BoundaryPair.builder()
                        .startAfter(info.getStartAfter())
                        .endBefore(info.getEndBefore())
                        .maxTokens(info.getMaxTokens())
                        .build();
                boolean dupPair = updatedPairs.stream().anyMatch(p ->
                        Objects.equals(p.getStartAfter(), info.getStartAfter())
                                && Objects.equals(p.getEndBefore(), info.getEndBefore()));
                if (!dupPair) updatedPairs.add(newPair);

                // Append new regex variant if not duplicate — this is the fix for the root cause
                List<RegexVariant> updatedVariants = new ArrayList<>(existingEntity.getRegexVariants());
                if (info.getRegex() != null && !info.getRegex().isEmpty()) {
                    RegexVariant newVariant = new RegexVariant(info.getRegex(), info.getGroup());
                    if (!updatedVariants.contains(newVariant)) {
                        updatedVariants.add(newVariant);
                        log.info("Added new regex variant for entity={} senderId={} regex='{}'",
                                info.getName(), existingEntity.getName(), info.getRegex());
                    }
                }

                entityMap.put(info.getName(), GlobalEntity.builder()
                        .name(existingEntity.getName())
                        .type(existingEntity.getType())
                        .regexVariants(updatedVariants)
                        .boundaryPairs(updatedPairs)
                        .build());
            } else {
                List<BoundaryPair> pairs = new ArrayList<>();
                if (info.getStartAfter() != null || info.getEndBefore() != null) {
                    pairs.add(BoundaryPair.builder()
                            .startAfter(info.getStartAfter())
                            .endBefore(info.getEndBefore())
                            .maxTokens(info.getMaxTokens())
                            .build());
                }
                List<RegexVariant> variants = new ArrayList<>();
                if (info.getRegex() != null && !info.getRegex().isEmpty()) {
                    variants.add(new RegexVariant(info.getRegex(), info.getGroup()));
                }
                entityMap.put(info.getName(), GlobalEntity.builder()
                        .name(info.getName())
                        .type(info.getType())
                        .regexVariants(variants)
                        .boundaryPairs(pairs)
                        .build());
            }
        }

        entityRepository.save(senderId, new ArrayList<>(entityMap.values()));
        log.info("Updated global entity list for senderId={}, total entities={}", senderId, entityMap.size());
    }
}
