package com.github.salilvnair.auditx.core.model;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

/**
 * Consumer-friendly request model to publish audit events without building the full canonical envelope.
 */
@Value
@Builder(builderClassName = "Builder")
@Jacksonized
public class AuditWriteRequest {
    @NonNull
    String eventType;

    @lombok.Builder.Default
    AuditSeverity severity = AuditSeverity.INFO;

    @lombok.Builder.Default
    AuditSource source = AuditSource.OTHER;

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

    @lombok.Singular("actorEntry")
    Map<String, Object> actor;

    @lombok.Singular("error")
    Map<String, Object> errorMap;

    public static class Builder {
        public Builder auditEventType(AuditEventType eventType) {
            return this.eventType(eventType.code());
        }
    }
}
