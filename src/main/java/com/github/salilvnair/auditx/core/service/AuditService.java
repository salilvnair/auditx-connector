package com.github.salilvnair.auditx.core.service;

import com.github.salilvnair.auditx.core.model.AuditSource;
import com.github.salilvnair.auditx.core.model.AuditStage;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public interface AuditService {
    void publish(AuditWriteRequest request);

    void publishInfo(AuditWriteRequest request);

    void publishWarn(AuditWriteRequest request);

    void publishError(AuditWriteRequest request);

    void publish(CanonicalAuditEnvelope envelope);

    /**
     * Convenience API for consumers that publish audit using stage + metadata maps.
     * `conversationId` stays mandatory to preserve correlation/idempotency contracts.
     */
    default void publish(String stage, String conversationId, Map<String, Object> metadata) {
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("stage is required");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }

        AuditWriteRequest.Builder builder = AuditWriteRequest.builder()
                .eventType(stage)
                .conversationId(conversationId)
                .source(AuditSource.OTHER);

        if (metadata != null && !metadata.isEmpty()) {
            builder.extraMap(metadata);
        }

        publishInfo(builder.build());
    }

    /**
     * Stage-driven utility publish where stage provides static event metadata.
     */
    default void publish(AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata) {
        publish(buildWriteRequest(stage, conversationId, traceId, metadata, null));
    }

    /**
     * Stage-driven utility publish that overlays stage/trace/metadata on a base request.
     */
    default void publish( AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, AuditWriteRequest baseRequest) {
        publish(buildWriteRequest(stage, conversationId, traceId, metadata, baseRequest));
    }

    /**
     * Stage-driven utility publish that overlays stage/trace/metadata on a base canonical envelope.
     */
    default void publish( AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, CanonicalAuditEnvelope baseEnvelope) {
        publish(buildCanonicalEnvelope(stage, conversationId, traceId, metadata, baseEnvelope));
    }

    private AuditWriteRequest buildWriteRequest( AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, AuditWriteRequest baseRequest) {
        requireStageAndConversation(stage, conversationId);

        AuditWriteRequest.Builder builder = AuditWriteRequest.builder()
                .eventType(stage.stageName())
                .source(stage.source())
                .severity(stage.severity())
                .conversationId(conversationId)
                .traceId(traceId);

        if (baseRequest != null) {
            builder.sessionId(baseRequest.getSessionId())
                    .groupId(baseRequest.getGroupId())
                    .interactionId(baseRequest.getInteractionId())
                    .spanId(baseRequest.getSpanId())
                    .idempotencyKey(baseRequest.getIdempotencyKey());
            copyMap(baseRequest.getBusinessKeys(), builder::businessKey);
            copyMap(baseRequest.getExtraMap(), builder::extra);
            copyMap(baseRequest.getActor(), builder::actorEntry);
            copyMap(baseRequest.getErrorMap(), builder::error);
        }

        copyMap(metadata, builder::extra);
        return builder.build();
    }

    private CanonicalAuditEnvelope buildCanonicalEnvelope( AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, CanonicalAuditEnvelope baseEnvelope) {
        requireStageAndConversation(stage, conversationId);

        CanonicalAuditEnvelope.Builder builder = baseEnvelope == null
                ? CanonicalAuditEnvelope.builder()
                : baseEnvelope.toBuilder();

        builder.eventType(stage.stageName())
                .source(stage.source())
                .severity(stage.severity())
                .conversationId(conversationId)
                .traceId(traceId);

        Map<String, Object> mergedExtra = new LinkedHashMap<>();
        if (baseEnvelope != null && baseEnvelope.getExtraMap() != null) {
            mergedExtra.putAll(baseEnvelope.getExtraMap());
        }
        if (metadata != null) {
            mergedExtra.putAll(metadata);
        }
        if (!mergedExtra.isEmpty()) {
            builder.extraMap(mergedExtra);
        }

        return builder.build();
    }

    private void requireStageAndConversation(AuditStage stage, String conversationId) {
        if (stage == null) {
            throw new IllegalArgumentException("stage is required");
        }
        if (isBlank(stage.stageName())) {
            throw new IllegalArgumentException("stageName is required");
        }
        if (stage.source() == null) {
            throw new IllegalArgumentException("stage source is required");
        }
        if (stage.severity() == null) {
            throw new IllegalArgumentException("stage severity is required");
        }
        if (isBlank(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
    }

    private boolean isBlank(String value) {
        return Objects.isNull(value) || value.isBlank();
    }

    private void copyMap(Map<String, Object> source, MapAppender appender) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            appender.append(entry.getKey(), entry.getValue());
        }
    }

    @FunctionalInterface
    interface MapAppender {
        void append(String key, Object value);
    }
}
