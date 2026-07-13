package com.sms.extraction.repository.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.extraction.domain.ExtractionResult;
import com.sms.extraction.repository.ExtractionResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

@Repository
public class DynamoDbExtractionResultRepository implements ExtractionResultRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbExtractionResultRepository.class);

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public DynamoDbExtractionResultRepository(DynamoDbClient dynamoDbClient,
                                               ObjectMapper objectMapper,
                                               @Value("${dynamodb.table.extracted-messages}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
    }

    @Override
    public void save(ExtractionResult result) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("messageId", AttributeValue.fromS(result.getMessageId()));
            item.put("sk", AttributeValue.fromS(result.getSenderId() + "#" + result.getTimestamp().toString()));
            item.put("senderId", AttributeValue.fromS(nullToEmpty(result.getSenderId())));
            item.put("rawSms", AttributeValue.fromS(nullToEmpty(result.getRawSms())));
            item.put("normalizedSms", AttributeValue.fromS(nullToEmpty(result.getNormalizedSms())));
            item.put("templateId", AttributeValue.fromS(nullToEmpty(result.getTemplateId())));
            item.put("category", AttributeValue.fromS(nullToEmpty(result.getCategory())));
            item.put("subcategory", AttributeValue.fromS(nullToEmpty(result.getSubcategory())));
            item.put("intent", AttributeValue.fromS(nullToEmpty(result.getIntent())));
            item.put("confidenceScore", AttributeValue.fromN(String.valueOf(result.getConfidenceScore())));
            item.put("timestamp", AttributeValue.fromS(result.getTimestamp() != null ? result.getTimestamp().toString() : ""));

            // Serialize extractedEntities as JSON string
            String entitiesJson = objectMapper.writeValueAsString(result.getExtractedEntities());
            item.put("extractedEntities", AttributeValue.fromS(entitiesJson));

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build();
            dynamoDbClient.putItem(putItemRequest);
            log.info("Saved extraction result messageId={}", result.getMessageId());
        } catch (Exception e) {
            log.error("Error saving extraction result messageId={}", result.getMessageId(), e);
            throw new RuntimeException("Failed to save extraction result", e);
        }
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
