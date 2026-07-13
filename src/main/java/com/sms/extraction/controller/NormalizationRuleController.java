package com.sms.extraction.controller;

import com.sms.extraction.domain.NormalizationRuleEntry;
import com.sms.extraction.repository.NormalizationRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/normalization-rules")
public class NormalizationRuleController {

    private final NormalizationRuleRepository normalizationRuleRepository;

    public NormalizationRuleController(NormalizationRuleRepository normalizationRuleRepository) {
        this.normalizationRuleRepository = normalizationRuleRepository;
    }

    @GetMapping
    public ResponseEntity<List<NormalizationRuleEntry>> getAll() {
        return ResponseEntity.ok(normalizationRuleRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<Void> upsert(@RequestBody UpsertRuleRequest request) {
        NormalizationRuleEntry entry = NormalizationRuleEntry.builder()
                .ruleType(request.ruleType())
                .mappings(request.mappings())
                .build();
        normalizationRuleRepository.save(entry);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{ruleType}/mappings/{key}")
    public ResponseEntity<Void> addMapping(
            @PathVariable String ruleType,
            @PathVariable String key,
            @RequestBody AddMappingRequest request) {

        List<NormalizationRuleEntry> all = normalizationRuleRepository.findAll();
        Optional<NormalizationRuleEntry> existing = all.stream()
                .filter(e -> ruleType.equals(e.getRuleType()))
                .findFirst();

        Map<String, String> mappings = new HashMap<>(existing.map(NormalizationRuleEntry::getMappings).orElse(Map.of()));
        mappings.put(key, request.value());

        normalizationRuleRepository.save(NormalizationRuleEntry.builder()
                .ruleType(ruleType)
                .mappings(mappings)
                .build());

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{ruleType}/mappings/{key}")
    public ResponseEntity<Void> removeMapping(
            @PathVariable String ruleType,
            @PathVariable String key) {

        List<NormalizationRuleEntry> all = normalizationRuleRepository.findAll();
        Optional<NormalizationRuleEntry> existing = all.stream()
                .filter(e -> ruleType.equals(e.getRuleType()))
                .findFirst();

        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, String> mappings = new HashMap<>(existing.get().getMappings());
        mappings.remove(key);

        normalizationRuleRepository.save(NormalizationRuleEntry.builder()
                .ruleType(ruleType)
                .mappings(mappings)
                .build());

        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{ruleType}")
    public ResponseEntity<Void> delete(@PathVariable String ruleType) {
        normalizationRuleRepository.deleteByRuleType(ruleType);
        return ResponseEntity.noContent().build();
    }

    public record UpsertRuleRequest(String ruleType, Map<String, String> mappings) {}

    public record AddMappingRequest(String value) {}
}
