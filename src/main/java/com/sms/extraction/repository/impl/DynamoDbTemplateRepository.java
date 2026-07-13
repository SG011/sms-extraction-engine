package com.sms.extraction.repository.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.extraction.domain.EntitySnapshot;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.LearnedTemplate;
import com.sms.extraction.repository.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DynamoDbTemplateRepository implements TemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTemplateRepository.class);

    private static final String ATTR_TEMPLATE_ID = "templateId";
    private static final String ATTR_SENDER_ID = "senderId";
    private static final String ATTR_CATEGORY = "category";
    private static final String ATTR_SUBCATEGORY = "subcategory";
    private static final String ATTR_INTENT = "intent";
    private static final String ATTR_CONFIDENCE_SCORE = "confidenceScore";
    private static final String ATTR_CANONICAL_TEMPLATE = "canonicalTemplate";
    private static final String ATTR_ENTITY_SNAPSHOTS = "entitySnapshots";
    private static final String ATTR_ORDERING = "ordering";
    private static final String ATTR_STATIC_TEXT_HASH = "staticTextHash";
    private static final String ATTR_PLACEHOLDER_SEQUENCE_HASH = "placeholderSequenceHash";
    private static final String ATTR_ACTIVE = "active";

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public DynamoDbTemplateRepository(DynamoDbClient dynamoDbClient,
                                       ObjectMapper objectMapper,
                                       @Value("${dynamodb.table.templates}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
    }

    @Override
    public Optional<LearnedTemplate> findById(String templateId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(ATTR_TEMPLATE_ID, AttributeValue.fromS(templateId)))
                .consistentRead(true)
                .build();
        try {
            GetItemResponse response = dynamoDbClient.getItem(request);
            if (!response.hasItem() || response.item().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(mapToLearnedTemplate(response.item()));
        } catch (Exception e) {
            log.error("Error fetching template by templateId={}", templateId, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<LearnedTemplate> findByStaticTextHash(String senderId, String hash) {
        // The static text hash index: senderStaticHash = senderId + "#" + hash stored as PK in GSI
        // We store senderId#staticTextHash as a GSI PK for efficient lookup
        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":senderHash", AttributeValue.fromS(senderId + "#" + hash));

        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(tableName)
                .indexName("staticTextHash-index")
                .keyConditionExpression("senderStaticHash = :senderHash")
                .expressionAttributeValues(expressionValues)
                .limit(1)
                .build();

        try {
            QueryResponse response = dynamoDbClient.query(queryRequest);
            if (response.items().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(mapToLearnedTemplate(response.items().get(0)));
        } catch (Exception e) {
            log.error("Error querying by staticTextHash for senderId={}, hash={}", senderId, hash, e);
            return Optional.empty();
        }
    }

    @Override
    public List<LearnedTemplate> findByPlaceholderSequenceHash(String senderId, String hash) {
        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":senderSeqHash", AttributeValue.fromS(senderId + "#" + hash));

        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(tableName)
                .indexName("placeholderSequenceHash-index")
                .keyConditionExpression("senderPlaceholderHash = :senderSeqHash")
                .expressionAttributeValues(expressionValues)
                .build();

        try {
            QueryResponse response = dynamoDbClient.query(queryRequest);
            List<LearnedTemplate> templates = new ArrayList<>();
            for (Map<String, AttributeValue> item : response.items()) {
                templates.add(mapToLearnedTemplate(item));
            }
            return templates;
        } catch (Exception e) {
            log.error("Error querying by placeholderSequenceHash for senderId={}, hash={}", senderId, hash, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void save(LearnedTemplate template) {
        try {
            Map<String, AttributeValue> item = mapToAttributeValueMap(template);
            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .build();
            dynamoDbClient.putItem(putItemRequest);
            log.info("Saved template templateId={}", template.getTemplateId());
        } catch (Exception e) {
            log.error("Error saving template templateId={}", template.getTemplateId(), e);
            throw new RuntimeException("Failed to save template", e);
        }
    }

    private Map<String, AttributeValue> mapToAttributeValueMap(LearnedTemplate template) throws JsonProcessingException {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ATTR_TEMPLATE_ID, AttributeValue.fromS(template.getTemplateId()));
        item.put(ATTR_SENDER_ID, AttributeValue.fromS(template.getSenderId()));
        item.put(ATTR_CATEGORY, AttributeValue.fromS(nullToEmpty(template.getCategory())));
        item.put(ATTR_SUBCATEGORY, AttributeValue.fromS(nullToEmpty(template.getSubcategory())));
        item.put(ATTR_INTENT, AttributeValue.fromS(nullToEmpty(template.getIntent())));
        item.put(ATTR_CONFIDENCE_SCORE, AttributeValue.fromN(String.valueOf(template.getConfidenceScore())));
        item.put(ATTR_CANONICAL_TEMPLATE, AttributeValue.fromS(nullToEmpty(template.getCanonicalTemplate())));
        item.put(ATTR_ORDERING, AttributeValue.fromL(
                template.getOrdering().stream().map(AttributeValue::fromS).toList()));
        item.put(ATTR_STATIC_TEXT_HASH, AttributeValue.fromS(nullToEmpty(template.getStaticTextHash())));
        item.put(ATTR_PLACEHOLDER_SEQUENCE_HASH, AttributeValue.fromS(nullToEmpty(template.getPlaceholderSequenceHash())));
        item.put(ATTR_ACTIVE, AttributeValue.fromBool(template.isActive()));

        // Serialize entity snapshots as native DynamoDB List/Map
        item.put(ATTR_ENTITY_SNAPSHOTS, AttributeValue.fromL(
                template.getEntitySnapshots().stream().map(snapshot -> {
                    Map<String, AttributeValue> m = new HashMap<>();
                    m.put("name",         AttributeValue.fromS(nullToEmpty(snapshot.getName())));
                    m.put("semanticType", AttributeValue.fromS(nullToEmpty(snapshot.getSemanticType())));
                    m.put("type",         AttributeValue.fromS(snapshot.getType() != null ? snapshot.getType().name() : ""));
                    m.put("regex",        AttributeValue.fromS(nullToEmpty(snapshot.getRegex())));
                    m.put("startAfter",   AttributeValue.fromS(nullToEmpty(snapshot.getStartAfter())));
                    m.put("endBefore",    AttributeValue.fromS(nullToEmpty(snapshot.getEndBefore())));
                    m.put("maxTokens",    AttributeValue.fromN(String.valueOf(snapshot.getMaxTokens())));
                    return AttributeValue.fromM(m);
                }).toList()));

        // GSI keys for hash lookups
        item.put("senderStaticHash", AttributeValue.fromS(template.getSenderId() + "#" + nullToEmpty(template.getStaticTextHash())));
        item.put("senderPlaceholderHash", AttributeValue.fromS(template.getSenderId() + "#" + nullToEmpty(template.getPlaceholderSequenceHash())));

        return item;
    }

    private LearnedTemplate mapToLearnedTemplate(Map<String, AttributeValue> item) {
        List<EntitySnapshot> snapshots = new ArrayList<>();
        AttributeValue snapshotsAv = item.get(ATTR_ENTITY_SNAPSHOTS);
        if (snapshotsAv != null && snapshotsAv.l() != null) {
            for (AttributeValue av : snapshotsAv.l()) {
                Map<String, AttributeValue> m = av.m();
                if (m == null) continue;
                String typeName = m.containsKey("type") ? m.get("type").s() : null;
                ExtractionRuleType type = (typeName != null && !typeName.isEmpty())
                        ? ExtractionRuleType.valueOf(typeName) : null;
                int maxTokens = m.containsKey("maxTokens") ? Integer.parseInt(m.get("maxTokens").n()) : 0;
                snapshots.add(EntitySnapshot.builder()
                        .name(m.containsKey("name") ? m.get("name").s() : null)
                        .semanticType(m.containsKey("semanticType") ? m.get("semanticType").s() : null)
                        .type(type)
                        .regex(m.containsKey("regex") ? m.get("regex").s() : null)
                        .startAfter(m.containsKey("startAfter") ? m.get("startAfter").s() : null)
                        .endBefore(m.containsKey("endBefore") ? m.get("endBefore").s() : null)
                        .maxTokens(maxTokens)
                        .build());
            }
        }

        List<String> ordering = new ArrayList<>();
        AttributeValue orderingAv = item.get(ATTR_ORDERING);
        if (orderingAv != null && orderingAv.l() != null) {
            ordering = orderingAv.l().stream().map(AttributeValue::s).toList();
        }

        double confidenceScore = 0.0;
        if (item.containsKey(ATTR_CONFIDENCE_SCORE) && item.get(ATTR_CONFIDENCE_SCORE).n() != null) {
            confidenceScore = Double.parseDouble(item.get(ATTR_CONFIDENCE_SCORE).n());
        }

        boolean active = true;
        if (item.containsKey(ATTR_ACTIVE) && item.get(ATTR_ACTIVE).bool() != null) {
            active = item.get(ATTR_ACTIVE).bool();
        }

        return LearnedTemplate.builder()
                .templateId(stringAttr(item, ATTR_TEMPLATE_ID))
                .senderId(stringAttr(item, ATTR_SENDER_ID))
                .category(stringAttr(item, ATTR_CATEGORY))
                .subcategory(stringAttr(item, ATTR_SUBCATEGORY))
                .intent(stringAttr(item, ATTR_INTENT))
                .confidenceScore(confidenceScore)
                .canonicalTemplate(stringAttr(item, ATTR_CANONICAL_TEMPLATE))
                .entitySnapshots(snapshots)
                .ordering(ordering)
                .staticTextHash(stringAttr(item, ATTR_STATIC_TEXT_HASH))
                .placeholderSequenceHash(stringAttr(item, ATTR_PLACEHOLDER_SEQUENCE_HASH))
                .active(active)
                .build();
    }

    private String stringAttr(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        return av != null ? av.s() : null;
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
