package com.github.salilvnair.auditx.core.config.hibernate;

import com.github.salilvnair.auditx.starter.config.AuditxEntityConfig;
import lombok.RequiredArgsConstructor;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AuditxPhysicalNamingStrategy extends PhysicalNamingStrategySnakeCaseImpl implements PhysicalNamingStrategy {

    private final AuditxEntityConfig config;

    @Override
    public Identifier toPhysicalTableName(Identifier identifier, JdbcEnvironment jdbcEnvironment) {
        if (identifier == null) {
            return null;
        }
        String text = identifier.getText();
        if (text != null) {
            // Config keys use the entity suffix: AUDITX_EVENT → EVENT, AUDITX_OUTBOX → OUTBOX
            String entityKey = text.toUpperCase().replaceFirst("^AUDITX_", "");
            Map<String, String> tables = config.getTables();
            if (tables.containsKey(entityKey)) {
                return Identifier.toIdentifier(tables.get(entityKey), identifier.isQuoted());
            }
        }
        return super.toPhysicalTableName(identifier, jdbcEnvironment);
    }

    @Override
    public Identifier toPhysicalCatalogName(Identifier identifier, JdbcEnvironment jdbcEnvironment) {
        return super.toPhysicalCatalogName(identifier, jdbcEnvironment);
    }

    @Override
    public Identifier toPhysicalSchemaName(Identifier identifier, JdbcEnvironment jdbcEnvironment) {
        return super.toPhysicalSchemaName(identifier, jdbcEnvironment);
    }

    @Override
    public Identifier toPhysicalSequenceName(Identifier identifier, JdbcEnvironment jdbcEnvironment) {
        return super.toPhysicalSequenceName(identifier, jdbcEnvironment);
    }

    @Override
    public Identifier toPhysicalColumnName(Identifier identifier, JdbcEnvironment jdbcEnvironment) {
        return super.toPhysicalColumnName(identifier, jdbcEnvironment);
    }
}
