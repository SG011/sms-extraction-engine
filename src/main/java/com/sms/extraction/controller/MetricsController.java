package com.sms.extraction.controller;

import com.sms.extraction.metrics.ExtractionMetrics;
import com.sms.extraction.metrics.MessageRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final ExtractionMetrics metrics;

    public MetricsController(ExtractionMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public ResponseEntity<ExtractionMetrics.Snapshot> get() {
        return ResponseEntity.ok(metrics.snapshot());
    }

    @GetMapping("/report/summary")
    public ResponseEntity<ExtractionMetrics.Snapshot> summary() {
        return ResponseEntity.ok(metrics.snapshot());
    }

    @GetMapping("/report/detail")
    public ResponseEntity<List<MessageRecord>> detail(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10000") int size) {
        List<MessageRecord> all = metrics.getMessageRecords();
        int from = Math.min(page * size, all.size());
        int to   = Math.min(from + size, all.size());
        return ResponseEntity.ok(new ArrayList<>(all.subList(from, to)));
    }

    @GetMapping("/failed-learnings")
    public ResponseEntity<List<ExtractionMetrics.FailedLearning>> failedLearnings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<ExtractionMetrics.FailedLearning> all = metrics.getFailedLearnings();
        int from = Math.min(page * size, all.size());
        int to   = Math.min(from + size, all.size());
        return ResponseEntity.ok(all.subList(from, to));
    }
}
