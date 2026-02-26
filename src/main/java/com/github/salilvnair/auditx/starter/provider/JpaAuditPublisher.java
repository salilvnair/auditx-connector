package com.github.salilvnair.auditx.starter.provider;

import com.github.salilvnair.auditx.core.model.AuditSource;
import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;
import com.github.salilvnair.auditx.core.persistence.AuditEventEntity;
import com.github.salilvnair.auditx.core.service.AuditPublisher;
import com.github.salilvnair.auditx.core.service.IdempotencyKeyFactory;
import com.github.salilvnair.auditx.starter.config.AuditConnectorProperties;
import com.github.salilvnair.auditx.starter.persistence.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Objects;
import java.util.UUID;

@RequiredArgsConstructor
public class JpaAuditPublisher implements AuditPublisher {
    private final AuditEventRepository repository;
    private final IdempotencyKeyFactory idempotencyKeyFactory;
    private final AuditConnectorProperties properties;
    private final AsyncTaskExecutor asyncTaskExecutor;

    @Override
    public void publish(CanonicalAuditEnvelope envelope) {
        if (!properties.isEnabled()) {
            return;
        }

        if (properties.isAsyncJpaPublish()) {
            asyncTaskExecutor.submit(() -> doPublish(envelope));
            return;
        }

        doPublish(envelope);
    }

    private void doPublish(CanonicalAuditEnvelope envelope) {
        validate(envelope);

        CanonicalAuditEnvelope enriched = enrichDefaults(envelope);

        if (properties.isEnforceIdempotency() && repository.existsByIdempotencyKey(enriched.getIdempotencyKey())) {
            return;
        }

        try {
            repository.save(AuditEventEntity.fromEnvelope(enriched));
        } catch (DataIntegrityViolationException ex) {
            if (!properties.isEnforceIdempotency()) {
                throw ex;
            }
        }
    }

    private CanonicalAuditEnvelope enrichDefaults(CanonicalAuditEnvelope envelope) {
        CanonicalAuditEnvelope withServiceFields = CanonicalAuditEnvelope.builder()
                .eventId(envelope.getEventId())
                .eventTime(envelope.getEventTime())
                .eventType(envelope.getEventType())
                .severity(envelope.getSeverity())
                .source(envelope.getSource())
                .serviceName(envelope.getServiceName())
                .serviceVersion(envelope.getServiceVersion())
                .environment(envelope.getEnvironment())
                .sessionId(envelope.getSessionId())
                .conversationId(envelope.getConversationId())
                .groupId(envelope.getGroupId())
                .interactionId(envelope.getInteractionId())
                .traceId(envelope.getTraceId())
                .spanId(envelope.getSpanId())
                .idempotencyKey(envelope.getIdempotencyKey())
                .businessKeys(envelope.getBusinessKeys())
                .extraMap(envelope.getExtraMap())
                .actor(envelope.getActor())
                .errorMap(envelope.getErrorMap())
                .build();

        if (!properties.isEnforceIdempotency()) {
            return withServiceFields;
        }

        if (isBlank(withServiceFields.getIdempotencyKey())) {
            return withServiceFields.withIdempotencyKey(idempotencyKeyFactory.create(withServiceFields));
        }

        return withServiceFields;
    }

    private boolean isBlank(String value) {
        return Objects.isNull(value) || value.isBlank();
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
}
