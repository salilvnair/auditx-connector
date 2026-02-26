package com.github.salilvnair.auditx.core.model;

/**
 * Contract for stage enums that carry static audit metadata.
 */
public interface AuditStage {
    String stageName();

    AuditSource source();

    AuditSeverity severity();
}
