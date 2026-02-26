package com.github.salilvnair.auditx.starter.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches ASYNC_DB mode.
 */
public class AsyncDbPublisherCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("audit.connector.publisher-type");
        if (value == null || value.isBlank()) {
            return true;
        }
        return "ASYNC_DB".equalsIgnoreCase(value);
    }
}
