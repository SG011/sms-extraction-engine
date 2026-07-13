package com.sms.extraction.controller;

import com.sms.extraction.domain.ExtractionResult;
import com.sms.extraction.service.SmsExtractionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/sms")
public class SmsController {

    private final SmsExtractionService smsExtractionService;

    public SmsController(SmsExtractionService smsExtractionService) {
        this.smsExtractionService = smsExtractionService;
    }

    @PostMapping("/process")
    public ResponseEntity<ExtractionResult> process(@RequestBody ProcessRequest request) {
        ExtractionResult result = smsExtractionService.process(request.senderId(), request.text());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<List<ExtractionResult>> batch(@RequestBody List<ProcessRequest> requests) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<ExtractionResult>> futures = requests.stream()
                    .map(r -> CompletableFuture.supplyAsync(
                            () -> smsExtractionService.process(r.senderId(), r.text()), executor))
                    .toList();

            List<ExtractionResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            return ResponseEntity.ok(results);
        } finally {
            executor.close();
        }
    }

    public record ProcessRequest(String senderId, String text) {}
}
