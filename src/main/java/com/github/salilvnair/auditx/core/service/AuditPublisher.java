package com.github.salilvnair.auditx.core.service;

import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;

public interface AuditPublisher {
    void publish(CanonicalAuditEnvelope envelope);
}
