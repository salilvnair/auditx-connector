package com.github.salilvnair.auditx.core.persistence;

import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generic persisted representation of canonical audit envelope.
 */
@Entity(name = "AuditEvent")
@Table(name = "AUDITX_EVENT")
@Getter
public class AuditEventEntity {
    @Id
    private UUID eventId;

    @Column(nullable = false)
    private Instant eventTime;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String source;

    @Column
    private String serviceName;

    @Column
    private String serviceVersion;

    @Column
    private String environment;

    @Column
    private String sessionId;

    @Column
    private String conversationId;

    @Column
    private String groupId;

    @Column
    private String interactionId;

    @Column
    private String traceId;

    @Column
    private String spanId;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> businessKeys = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> extraMap = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> actor = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> errorMap = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> eventPayload = new HashMap<>();

    public static AuditEventEntity fromEnvelope(CanonicalAuditEnvelope envelope) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.eventId = envelope.getEventId();
        entity.eventTime = envelope.getEventTime();
        entity.eventType = envelope.getEventType();
        entity.severity = envelope.getSeverity().name();
        entity.source = envelope.getSource().name();
        entity.serviceName = envelope.getServiceName();
        entity.serviceVersion = envelope.getServiceVersion();
        entity.environment = envelope.getEnvironment();
        entity.sessionId = envelope.getSessionId();
        entity.conversationId = envelope.getConversationId();
        entity.groupId = envelope.getGroupId();
        entity.interactionId = envelope.getInteractionId();
        entity.traceId = envelope.getTraceId();
        entity.spanId = envelope.getSpanId();
        entity.idempotencyKey = envelope.getIdempotencyKey();
        entity.businessKeys = new HashMap<>(envelope.getBusinessKeys());
        entity.extraMap = new HashMap<>(envelope.getExtraMap());
        entity.actor = new HashMap<>(envelope.getActor());
        entity.errorMap = new HashMap<>(envelope.getErrorMap());
        entity.eventPayload = toEventPayload(envelope);
        return entity;
    }

    private static Map<String, Object> toEventPayload(CanonicalAuditEnvelope envelope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", envelope.getEventId());
        payload.put("eventTime", envelope.getEventTime());
        payload.put("eventType", envelope.getEventType());
        payload.put("severity", envelope.getSeverity() == null ? null : envelope.getSeverity().name());
        payload.put("source", envelope.getSource() == null ? null : envelope.getSource().name());
        payload.put("serviceName", envelope.getServiceName());
        payload.put("serviceVersion", envelope.getServiceVersion());
        payload.put("environment", envelope.getEnvironment());
        payload.put("sessionId", envelope.getSessionId());
        payload.put("conversationId", envelope.getConversationId());
        payload.put("groupId", envelope.getGroupId());
        payload.put("interactionId", envelope.getInteractionId());
        payload.put("traceId", envelope.getTraceId());
        payload.put("spanId", envelope.getSpanId());
        payload.put("idempotencyKey", envelope.getIdempotencyKey());
        payload.put("businessKeys", envelope.getBusinessKeys());
        payload.put("extraMap", envelope.getExtraMap());
        payload.put("actor", envelope.getActor());
        payload.put("errorMap", envelope.getErrorMap());
        return payload;
    }
}
