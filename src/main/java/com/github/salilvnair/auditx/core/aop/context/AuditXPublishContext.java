package com.github.salilvnair.auditx.core.aop.context;

import com.github.salilvnair.auditx.core.aop.annotation.AuditX;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import org.aspectj.lang.ProceedingJoinPoint;

import java.util.Map;

/**
 * Snapshot of everything known at publish time.
 * Passed to every AuditXContextInterceptor before auditService.publish() is called.
 *
 * The builder is the only mutable field — interceptors may enrich it freely.
 * All other fields are read-only context.
 */
public final class AuditXPublishContext {

    private final ProceedingJoinPoint joinPoint;
    private final AuditX auditX;
    private final long durationMs;
    private final Throwable error;
    private final Map<String, Object> snapshot;
    private final Map<String, String> tagSnapshot;
    private final AuditWriteRequest.Builder builder;

    public AuditXPublishContext(
            ProceedingJoinPoint joinPoint,
            AuditX auditX,
            long durationMs,
            Throwable error,
            Map<String, Object> snapshot,
            Map<String, String> tagSnapshot,
            AuditWriteRequest.Builder builder) {
        this.joinPoint = joinPoint;
        this.auditX = auditX;
        this.durationMs = durationMs;
        this.error = error;
        this.snapshot = snapshot;
        this.tagSnapshot = tagSnapshot;
        this.builder = builder;
    }

    /** The AspectJ join point — use to inspect the method, declaring type, or args. */
    public ProceedingJoinPoint getJoinPoint() { return joinPoint; }

    /** The @AuditX annotation as declared on the method. */
    public AuditX getAuditX() { return auditX; }

    /** Wall-clock duration of the intercepted method in milliseconds. */
    public long getDurationMs() { return durationMs; }

    /** Non-null if the method threw an exception; null on success. */
    public Throwable getError() { return error; }

    /** True when the method threw an exception. */
    public boolean isError() { return error != null; }

    /** Unmodifiable snapshot of AuditXContext entries accumulated during the method. */
    public Map<String, Object> getSnapshot() { return snapshot; }

    /** Unmodifiable snapshot of AuditXContext tags accumulated during the method. */
    public Map<String, String> getTagSnapshot() { return tagSnapshot; }

    /**
     * The builder that will be passed to auditService.publish() after all interceptors run.
     * Interceptors may call any builder method to add or override fields.
     */
    public AuditWriteRequest.Builder getBuilder() { return builder; }
}
