package com.github.salilvnair.auditx.starter.web;

import com.github.salilvnair.auditx.core.model.AuditSeverity;
import com.github.salilvnair.auditx.core.model.AuditSource;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class AuditPublishApiRequest {
    private String stage;
    private String conversationId;
    private String traceId;
    private AuditSource source;
    private AuditSeverity severity;
    private Map<String, Object> metadata;
    private AuditWriteRequest auditWriteRequest;
    private CanonicalAuditEnvelope canonicalEnvelope;
}
