package com.sms.extraction.metrics;

import com.sms.extraction.domain.LlmReason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class ExtractionMetrics {

    private static final int MAX_RECORDS = 200_000;

    private final AtomicLong totalMessages                 = new AtomicLong();
    private final AtomicLong templateHits                  = new AtomicLong();
    private final AtomicLong llmCalls                      = new AtomicLong();
    private final AtomicLong templatesLearned              = new AtomicLong();
    private final AtomicLong templatesSkippedLowConfidence = new AtomicLong();
    private final AtomicLong llmCallsFailed                = new AtomicLong();
    private final AtomicLong redundantLlmCalls             = new AtomicLong();

    private final Map<LlmReason, AtomicLong> llmReasonCounters = new EnumMap<>(LlmReason.class);

    private final AtomicLong totalInputTokens    = new AtomicLong();
    private final AtomicLong totalOutputTokens   = new AtomicLong();
    private final AtomicLong totalCachedTokens   = new AtomicLong();
    private final AtomicLong totalReasoningTokens = new AtomicLong();

    private final List<Long> llmLatencies          = new CopyOnWriteArrayList<>();
    private final List<Long> templateHitLatencies  = new CopyOnWriteArrayList<>();
    private final List<MessageRecord> messageRecords = new CopyOnWriteArrayList<>();

    public ExtractionMetrics() {
        for (LlmReason reason : LlmReason.values()) {
            llmReasonCounters.put(reason, new AtomicLong());
        }
    }

    public void incrementTotalMessages()                 { totalMessages.incrementAndGet(); }
    public void incrementTemplateHits()                  { templateHits.incrementAndGet(); }
    public void incrementLlmCalls()                      { llmCalls.incrementAndGet(); }
    public void incrementTemplatesLearned()              { templatesLearned.incrementAndGet(); }
    public void incrementTemplatesSkippedLowConfidence() { templatesSkippedLowConfidence.incrementAndGet(); }
    public void incrementLlmCallsFailed()                { llmCallsFailed.incrementAndGet(); }
    public void incrementRedundantLlmCalls()             { redundantLlmCalls.incrementAndGet(); }

    public void incrementLlmReason(LlmReason reason) {
        if (reason != null) llmReasonCounters.get(reason).incrementAndGet();
    }

    public void recordLlmLatency(long ms)          { llmLatencies.add(ms); }
    public void recordTemplateHitLatency(long ms)  { templateHitLatencies.add(ms); }

    public void addTokenUsage(long input, long output, long cached, long reasoning) {
        totalInputTokens.addAndGet(input);
        totalOutputTokens.addAndGet(output);
        totalCachedTokens.addAndGet(cached);
        totalReasoningTokens.addAndGet(reasoning);
    }

    public void recordMessage(MessageRecord record) {
        if (messageRecords.size() < MAX_RECORDS) {
            messageRecords.add(record);
        }
    }

    public List<MessageRecord> getMessageRecords() {
        return Collections.unmodifiableList(messageRecords);
    }

    public List<FailedLearning> getFailedLearnings() {
        return messageRecords.stream()
                .filter(r -> "LLM".equals(r.getPath()) && r.getConfidenceScore() < confidenceThresholdForReport())
                .map(r -> new FailedLearning(r.getSenderId(), r.getNormalizedSms(),
                        r.getLlmReason() != null ? r.getLlmReason().name() : "UNKNOWN", r.getConfidenceScore()))
                .collect(java.util.stream.Collectors.toList());
    }

    private double confidenceThresholdForReport() { return 0.5; }

    public record FailedLearning(String senderId, String normalizedSms, String reason, double confidenceScore) {}

    public Snapshot snapshot() {
        Map<String, Long> reasonBreakdown = new HashMap<>();
        llmReasonCounters.forEach((k, v) -> reasonBreakdown.put(k.name(), v.get()));

        long total = totalMessages.get();
        long hits  = templateHits.get();
        String hitRate = total > 0
                ? String.format("%.1f%%", (hits * 100.0) / total)
                : "0.0%";

        return new Snapshot(
                total,
                hits,
                llmCalls.get(),
                templatesLearned.get(),
                templatesSkippedLowConfidence.get(),
                llmCallsFailed.get(),
                redundantLlmCalls.get(),
                hitRate,
                reasonBreakdown,
                percentiles(llmLatencies),
                percentiles(templateHitLatencies),
                new TokenUsage(
                        totalInputTokens.get(),
                        totalOutputTokens.get(),
                        totalCachedTokens.get(),
                        totalReasoningTokens.get()
                )
        );
    }

    private LatencyStats percentiles(List<Long> values) {
        if (values.isEmpty()) return new LatencyStats(0, 0, 0, 0);
        List<Long> sorted = values.stream().sorted().collect(Collectors.toList());
        long avg = (long) sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long p50 = sorted.get((int) (sorted.size() * 0.50));
        long p95 = sorted.get((int) (sorted.size() * 0.95));
        long p99 = sorted.get((int) (sorted.size() * 0.99));
        return new LatencyStats(avg, p50, p95, p99);
    }

    public record LatencyStats(long avgMs, long p50Ms, long p95Ms, long p99Ms) {}

    public record TokenUsage(long inputTokens, long outputTokens, long cachedTokens, long reasoningTokens) {}

    public record Snapshot(
            long totalMessages,
            long templateHits,
            long llmCalls,
            long templatesLearned,
            long templatesSkippedLowConfidence,
            long llmCallsFailed,
            long redundantLlmCalls,
            String hitRate,
            Map<String, Long> llmReasonBreakdown,
            LatencyStats llmLatency,
            LatencyStats templateHitLatency,
            TokenUsage tokenUsage
    ) {}
}
