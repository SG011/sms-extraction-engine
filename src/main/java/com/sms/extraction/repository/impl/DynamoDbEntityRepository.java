package com.sms.extraction.repository.impl;

import com.sms.extraction.domain.BoundaryPair;
import com.sms.extraction.domain.ExtractionRuleType;
import com.sms.extraction.domain.GlobalEntity;
import com.sms.extraction.domain.RegexVariant;
import com.sms.extraction.repository.EntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DynamoDbEntityRepository implements EntityRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbEntityRepository.class);

    private static final String ATTR_SENDER_ID = "senderId";
    private static final String ATTR_ENTITIES  = "entities";

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbEntityRepository(DynamoDbClient dynamoDbClient,
                                     @Value("${dynamodb.table.entities}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public List<GlobalEntity> findBySenderId(String senderId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(ATTR_SENDER_ID, AttributeValue.fromS(senderId)))
                .consistentRead(true)
                .build();
        try {
            GetItemResponse response = dynamoDbClient.getItem(request);
            if (!response.hasItem() || response.item().isEmpty()) return new ArrayList<>();
            return deserializeEntities(response.item().get(ATTR_ENTITIES));
        } catch (Exception e) {
            log.error("Error fetching entities for senderId={}", senderId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public void save(String senderId, List<GlobalEntity> entities) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put(ATTR_SENDER_ID, AttributeValue.fromS(senderId));
            item.put(ATTR_ENTITIES, AttributeValue.fromL(entities.stream()
                    .map(this::toAttributeValue)
                    .toList()));
            dynamoDbClient.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
            log.info("Saved {} entities for senderId={}", entities.size(), senderId);
        } catch (Exception e) {
            log.error("Error saving entities for senderId={}", senderId, e);
            throw new RuntimeException("Failed to save entities for sender: " + senderId, e);
        }
    }

    private AttributeValue toAttributeValue(GlobalEntity entity) {
        Map<String, AttributeValue> m = new HashMap<>();
        m.put("name", AttributeValue.fromS(entity.getName()));
        m.put("type", AttributeValue.fromS(entity.getType() != null ? entity.getType().name() : "REGEX"));

        m.put("regexVariants", AttributeValue.fromL(entity.getRegexVariants().stream()
                .map(v -> AttributeValue.fromM(Map.of(
                        "regex", AttributeValue.fromS(v.getRegex()),
                        "group", AttributeValue.fromN(String.valueOf(v.getGroup())))))
                .toList()));

        m.put("boundaryPairs", AttributeValue.fromL(entity.getBoundaryPairs().stream()
                .map(bp -> AttributeValue.fromM(Map.of(
                        "startAfter", AttributeValue.fromS(bp.getStartAfter() != null ? bp.getStartAfter() : ""),
                        "endBefore",  AttributeValue.fromS(bp.getEndBefore()  != null ? bp.getEndBefore()  : ""),
                        "maxTokens",  AttributeValue.fromN(String.valueOf(bp.getMaxTokens())))))
                .toList()));

        return AttributeValue.fromM(m);
    }

    private List<GlobalEntity> deserializeEntities(AttributeValue entitiesAttr) {
        List<GlobalEntity> result = new ArrayList<>();
        if (entitiesAttr == null || entitiesAttr.l() == null) return result;
        for (AttributeValue entityAv : entitiesAttr.l()) {
            Map<String, AttributeValue> m = entityAv.m();
            if (m == null) continue;

            String typeName = m.containsKey("type") ? m.get("type").s() : null;
            ExtractionRuleType type = typeName != null ? ExtractionRuleType.valueOf(typeName) : null;

            List<RegexVariant> variants = new ArrayList<>();
            if (m.containsKey("regexVariants") && m.get("regexVariants").l() != null) {
                for (AttributeValue vAv : m.get("regexVariants").l()) {
                    Map<String, AttributeValue> vm = vAv.m();
                    if (vm == null) continue;
                    String r = vm.containsKey("regex") ? vm.get("regex").s() : null;
                    int g = vm.containsKey("group") ? Integer.parseInt(vm.get("group").n()) : 0;
                    if (r != null && !r.isEmpty()) variants.add(new RegexVariant(r, g));
                }
            }

            List<BoundaryPair> boundaryPairs = new ArrayList<>();
            if (m.containsKey("boundaryPairs") && m.get("boundaryPairs").l() != null) {
                for (AttributeValue bpAv : m.get("boundaryPairs").l()) {
                    Map<String, AttributeValue> bm = bpAv.m();
                    if (bm == null) continue;
                    int maxTokens = bm.containsKey("maxTokens") ? Integer.parseInt(bm.get("maxTokens").n()) : 0;
                    boundaryPairs.add(BoundaryPair.builder()
                            .startAfter(bm.containsKey("startAfter") ? bm.get("startAfter").s() : null)
                            .endBefore(bm.containsKey("endBefore") ? bm.get("endBefore").s() : null)
                            .maxTokens(maxTokens)
                            .build());
                }
            }

            result.add(GlobalEntity.builder()
                    .name(m.containsKey("name") ? m.get("name").s() : null)
                    .type(type)
                    .regexVariants(variants)
                    .boundaryPairs(boundaryPairs)
                    .build());
        }
        return result;
    }

}
