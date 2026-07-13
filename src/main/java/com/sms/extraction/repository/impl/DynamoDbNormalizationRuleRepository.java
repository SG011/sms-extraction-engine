package com.sms.extraction.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.extraction.domain.NormalizationRuleEntry;
import com.sms.extraction.repository.NormalizationRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DynamoDbNormalizationRuleRepository implements NormalizationRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbNormalizationRuleRepository.class);

    private static final String ATTR_RULE_TYPE = "ruleType";
    private static final String ATTR_MAPPINGS = "mappings";

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public DynamoDbNormalizationRuleRepository(DynamoDbClient dynamoDbClient,
                                                ObjectMapper objectMapper,
                                                @Value("${dynamodb.table.normalization}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
    }

    @Override
    public List<NormalizationRuleEntry> findAll() {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(tableName)
                .build();

        List<NormalizationRuleEntry> rules = new ArrayList<>();
        try {
            ScanResponse response = dynamoDbClient.scan(scanRequest);
            for (Map<String, AttributeValue> item : response.items()) {
                String ruleType = item.containsKey(ATTR_RULE_TYPE) ? item.get(ATTR_RULE_TYPE).s() : null;
                Map<String, String> mappings = new HashMap<>();

                if (item.containsKey(ATTR_MAPPINGS)) {
                    String mappingsJson = item.get(ATTR_MAPPINGS).s();
                    if (mappingsJson != null && !mappingsJson.isEmpty()) {
                        try {
                            mappings = objectMapper.readValue(mappingsJson, new TypeReference<Map<String, String>>() {});
                        } catch (Exception e) {
                            log.error("Error deserializing mappings for ruleType={}", ruleType, e);
                        }
                    }
                }

                rules.add(NormalizationRuleEntry.builder()
                        .ruleType(ruleType)
                        .mappings(mappings)
                        .build());
            }
        } catch (Exception e) {
            log.error("Error fetching normalization rules", e);
        }
        return rules;
    }

    @Override
    public void save(NormalizationRuleEntry entry) {
        try {
            String mappingsJson = objectMapper.writeValueAsString(entry.getMappings());
            Map<String, AttributeValue> item = new HashMap<>();
            item.put(ATTR_RULE_TYPE, AttributeValue.builder().s(entry.getRuleType()).build());
            item.put(ATTR_MAPPINGS, AttributeValue.builder().s(mappingsJson).build());

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build());
        } catch (Exception e) {
            log.error("Error saving normalization rule ruleType={}", entry.getRuleType(), e);
            throw new RuntimeException("Failed to save normalization rule", e);
        }
    }

    @Override
    public void deleteByRuleType(String ruleType) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put(ATTR_RULE_TYPE, AttributeValue.builder().s(ruleType).build());

            dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                    .tableName(tableName)
                    .key(key)
                    .build());
        } catch (Exception e) {
            log.error("Error deleting normalization rule ruleType={}", ruleType, e);
            throw new RuntimeException("Failed to delete normalization rule", e);
        }
    }
}
