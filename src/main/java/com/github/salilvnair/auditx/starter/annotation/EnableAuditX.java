package com.github.salilvnair.auditx.starter.annotation;

import com.github.salilvnair.auditx.starter.autoconfigure.AuditConnectorAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly enables AuditX connector beans in a Spring Boot application.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(AuditConnectorAutoConfiguration.class)
public @interface EnableAuditX {
}
