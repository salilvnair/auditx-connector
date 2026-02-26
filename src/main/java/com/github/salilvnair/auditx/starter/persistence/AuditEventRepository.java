package com.github.salilvnair.auditx.starter.persistence;

import com.github.salilvnair.auditx.core.persistence.AuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
    boolean existsByIdempotencyKey(String idempotencyKey);
}
