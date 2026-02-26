package com.github.salilvnair.auditx.starter.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.auditx.core.model.AuditSource;
import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;
import com.github.salilvnair.auditx.core.service.AuditPublisher;
import com.github.salilvnair.auditx.core.service.IdempotencyKeyFactory;
import com.github.salilvnair.auditx.starter.config.AuditConnectorProperties;
import com.github.salilvnair.auditx.starter.config.KafkaMessageKeyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * Kafka-backed publisher that writes canonical envelope as JSON.
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaAuditPublisher implements AuditPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final IdempotencyKeyFactory idempotencyKeyFactory;
    private final AuditConnectorProperties properties;
    private final AsyncTaskExecutor asyncTaskExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(CanonicalAuditEnvelope envelope) {
        if (!properties.isEnabled()) {
            return;
        }

        if (properties.isAsyncKafkaPublish()) {
            asyncTaskExecutor.submit(() -> doPublish(envelope));
            return;
        }

        doPublish(envelope);
    }

    private void doPublish(CanonicalAuditEnvelope envelope) {
        validate(envelope);
        CanonicalAuditEnvelope enriched = enrichDefaults(envelope);
        String key = messageKey(enriched);
        String payload = toJson(enriched);
        kafkaTemplate.send(properties.getKafka().getTopic(), key, payload);
    }

    private CanonicalAuditEnvelope enrichDefaults(CanonicalAuditEnvelope envelope) {
        if (!properties.isEnforceIdempotency()) {
            return envelope;
        }
        if (isBlank(envelope.getIdempotencyKey())) {
            return envelope.withIdempotencyKey(idempotencyKeyFactory.create(envelope));
        }
        return envelope;
    }

    private String messageKey(CanonicalAuditEnvelope envelope) {
        KafkaMessageKeyType keyType = properties.getKafka().getMessageKeyType();
        if (keyType == KafkaMessageKeyType.EVENT_ID) {
            return envelope.getEventId().toString();
        }
        if (keyType == KafkaMessageKeyType.CONVERSATION_ID) {
            return isBlank(envelope.getConversationId()) ? envelope.getEventId().toString() : envelope.getConversationId();
        }

        if (!isBlank(envelope.getIdempotencyKey())) {
            return envelope.getIdempotencyKey();
        }
        return envelope.getEventId().toString();
    }

    private String toJson(CanonicalAuditEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit envelope for Kafka publish", ex);
        }
    }

    private void validate(CanonicalAuditEnvelope envelope) {
        if (isBlank(envelope.getConversationId())) {
            throw new IllegalArgumentException("conversationId is required and must be a UUID");
        }

        try {
            UUID.fromString(envelope.getConversationId());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("conversationId must be a valid UUID", ex);
        }

        if (envelope.getSource() == AuditSource.UI && isBlank(envelope.getSessionId())) {
            throw new IllegalArgumentException("sessionId is required when source is UI");
        }
    }

    private boolean isBlank(String value) {
        return Objects.isNull(value) || value.isBlank();
    }
}
