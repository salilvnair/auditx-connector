package com.github.salilvnair.auditx.starter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "auditx.entity")
public class AuditxEntityConfig {
    /**
     * Example:
     * auditx.entity.tables.EVENT=custom_audit_event_table
     */
    private Map<String, String> tables = new HashMap<>();
}
