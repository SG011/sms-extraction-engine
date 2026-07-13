package com.sms.extraction.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sms.extraction.domain.ExtractionResult;
import com.sms.extraction.service.SmsExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SmsKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(SmsKafkaConsumer.class);

    private final SmsExtractionService smsExtractionService;
    private final ObjectMapper objectMapper;

    public SmsKafkaConsumer(SmsExtractionService smsExtractionService, ObjectMapper objectMapper) {
        this.smsExtractionService = smsExtractionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${kafka.topic}", groupId = "${kafka.group-id}")
    public void consume(String message) {
        log.debug("Received Kafka message: {}", message);
        try {
            JsonNode json = objectMapper.readTree(message);
            String senderId = json.path("senderId").asText(null);
            String text = json.path("text").asText(null);

            if (senderId == null || senderId.isBlank()) {
                log.warn("Received Kafka message with missing senderId, skipping");
                return;
            }
            if (text == null || text.isBlank()) {
                log.warn("Received Kafka message with missing text for senderId={}, skipping", senderId);
                return;
            }

            ExtractionResult result = smsExtractionService.process(senderId, text);
            log.info("Processed SMS senderId={} messageId={} category={}", senderId, result.getMessageId(), result.getCategory());
        } catch (Exception e) {
            log.error("Error processing Kafka message: {}", message, e);
            // Do not re-throw — failed messages are logged but not re-queued
        }
    }
}
