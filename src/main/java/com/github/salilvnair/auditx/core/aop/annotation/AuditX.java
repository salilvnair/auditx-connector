package com.github.salilvnair.auditx.core.aop.annotation;

import com.github.salilvnair.auditx.core.model.AuditSeverity;
import com.github.salilvnair.auditx.core.model.AuditSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring bean method for end-to-end audit capture.
 * <p>
 * The AuditableAspect will:
 *  - intercept the method
 *  - collect all AuditXContext.record(...) calls made during its execution
 *  - publish one AuditWriteRequest via AuditService on method exit
 *  - clear the thread-local context
 * <p>
 * Nesting-safe: only the outermost @AuditX method in a call stack
 * triggers the publish+clear. Inner annotated methods only contribute records.
 * <p>
 * Usage:
 * <pre>{@code
 *   @AuditX(eventType = "ChangeUserRole", source = AuditSource.SYSTEM,
 *           conversationId = "#request.header.userId", traceId = "#request.header.traceId")
 *   public void changeUserRole(ChangeUserRoleRequest request) {
 *       AuditXContext.record("adminUser", adminUser);
 *       AuditXContext.record("testApproved", testApproved);
 *   }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditX {

    /** Event type / stage name written to the audit record. */
    String eventType();

    /** Source system. Defaults to SYSTEM. */
    AuditSource source() default AuditSource.SYSTEM;

    /** Severity on success. */
    AuditSeverity severity() default AuditSeverity.INFO;

    /** Severity on error. */
    AuditSeverity errorSeverity() default AuditSeverity.ERROR;

    /**
     * Optional conversation / correlation id SpEL expression evaluated against method args.
     * Example: "#request.header.requestDataId"
     * Leave blank to skip.
     */
    String conversationId() default "";

    /**
     * Optional trace id SpEL expression evaluated against method args.
     * Example: "#request.header.traceId"
     * Leave blank to skip.
     */
    String traceId() default "";

    /**
     * Optional group id SpEL expression evaluated against method args.
     * Example: "#request.header.groupId"
     * Leave blank to skip.
     */

    String groupId() default "";

    /**
     * Optional interaction id SpEL expression evaluated against method args.
     * Example: "#request.header.interactionId"
     * Leave blank to skip.
     */
    String interactionId() default "";

    /**
     * Optional SpEL condition evaluated against method args.
     * When the expression evaluates to Boolean false the entire audit event is skipped —
     * the method still runs normally, only publishing is suppressed.
     * Leave blank to always publish.
     * Example: "#request.auditEnabled == true"
     */
    String condition() default "";
}