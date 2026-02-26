package com.github.salilvnair.auditx.starter.outbox;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class OutboxRecord {
    long id;
    String stage;
    String conversationId;
    String traceId;
    String source;
    String severity;
    Map<String, Object> metadata;
    Map<String, Object> auditWriteRequest;
    Map<String, Object> canonicalEnvelope;
    int retryCount;
    int maxRetries;
}
