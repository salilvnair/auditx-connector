package com.github.salilvnair.auditx.core.model;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical audit envelope that is generic and reusable across projects.
 */
@Value
@Builder(toBuilder = true, builderClassName = "Builder")
@Jacksonized
public class CanonicalAuditEnvelope {
    @lombok.Builder.Default
    UUID eventId = UUID.randomUUID();

    @lombok.Builder.Default
    Instant eventTime = Instant.now();

    @NonNull
    String eventType;

    @lombok.Builder.Default
    AuditSeverity severity = AuditSeverity.INFO;

    @lombok.Builder.Default
    AuditSource source = AuditSource.OTHER;

    String serviceName;
    String serviceVersion;
    String environment;
    String sessionId;
    String conversationId;
    String groupId;
    String interactionId;
    String traceId;
    String spanId;
    String idempotencyKey;

    @lombok.Singular("businessKey")
    Map<String, Object> businessKeys;

    @lombok.Singular("extra")
    Map<String, Object> extraMap;

    /** Low-cardinality string labels (tenant, env, region) stored in their own DB column. */
    @lombok.Singular("tag")
    Map<String, String> tags;

    @lombok.Singular("actorEntry")
    Map<String, Object> actor;

    @lombok.Singular("error")
    Map<String, Object> errorMap;

    public CanonicalAuditEnvelope withConversationId(String conversationId) {
        return this.toBuilder().conversationId(conversationId).build();
    }

    public CanonicalAuditEnvelope withIdempotencyKey(String idempotencyKey) {
        return this.toBuilder().idempotencyKey(idempotencyKey).build();
    }

    public static class Builder {
        public Builder auditEventType(AuditEventType eventType) {
            return this.eventType(eventType.code());
        }
    }
}
