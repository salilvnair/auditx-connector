package com.github.salilvnair.auditx.starter.service;

import com.github.salilvnair.auditx.core.model.AuditSeverity;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;
import com.github.salilvnair.auditx.core.service.AuditPublisher;
import com.github.salilvnair.auditx.core.service.AuditService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultAuditService implements AuditService {
    private final AuditPublisher auditPublisher;

    @Override
    public void publish(AuditWriteRequest request) {
        publishWithSeverity(request, request.getSeverity());
    }

    @Override
    public void publish(CanonicalAuditEnvelope envelope) {
        auditPublisher.publish(envelope);
    }

    @Override
    public void publishInfo(AuditWriteRequest request) {
        publishWithSeverity(request, AuditSeverity.INFO);
    }

    @Override
    public void publishWarn(AuditWriteRequest request) {
        publishWithSeverity(request, AuditSeverity.WARN);
    }

    @Override
    public void publishError(AuditWriteRequest request) {
        if (request.getErrorMap() == null || request.getErrorMap().isEmpty()) {
            throw new IllegalArgumentException("errorMap is required for publishError");
        }
        publishWithSeverity(request, AuditSeverity.ERROR);
    }

    private void publishWithSeverity(AuditWriteRequest request, AuditSeverity severity) {
        CanonicalAuditEnvelope envelope = CanonicalAuditEnvelope.builder()
                .eventType(request.getEventType())
                .severity(severity)
                .source(request.getSource())
                .sessionId(request.getSessionId())
                .conversationId(request.getConversationId())
                .groupId(request.getGroupId())
                .interactionId(request.getInteractionId())
                .traceId(request.getTraceId())
                .spanId(request.getSpanId())
                .idempotencyKey(request.getIdempotencyKey())
                .businessKeys(request.getBusinessKeys())
                .extraMap(request.getExtraMap())
                .actor(request.getActor())
                .errorMap(request.getErrorMap())
                .build();

        auditPublisher.publish(envelope);
    }
}
