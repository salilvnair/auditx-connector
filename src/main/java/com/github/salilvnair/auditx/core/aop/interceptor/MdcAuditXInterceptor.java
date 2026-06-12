package com.github.salilvnair.auditx.core.aop.interceptor;

import com.github.salilvnair.auditx.core.aop.context.AuditXPublishContext;
import org.slf4j.MDC;

/**
 * Built-in interceptor that fills traceId and spanId from SLF4J MDC when
 * the @AuditX annotation did not provide SpEL expressions for those fields.
 *
 * Opt-in: register as a Spring bean or enable via property:
 *   audit.connector.mdc-interceptor.enabled=true
 *
 * MDC keys used:
 *   traceId → "traceId"  (Spring Sleuth / Micrometer Tracing default)
 *   spanId  → "spanId"
 */
public class MdcAuditXInterceptor implements AuditXContextInterceptor {

    @Override
    public void intercept(AuditXPublishContext ctx) {
        if (ctx.getAuditX().traceId().isBlank()) {
            String traceId = MDC.get("traceId");
            if (traceId != null) {
                ctx.getBuilder().traceId(traceId);
            }
        }

        // @AuditX has no spanId expression — always try MDC
        String spanId = MDC.get("spanId");
        if (spanId != null) {
            ctx.getBuilder().spanId(spanId);
        }
    }
}
