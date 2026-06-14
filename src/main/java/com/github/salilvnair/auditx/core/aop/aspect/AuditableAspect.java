package com.github.salilvnair.auditx.core.aop.aspect;

import com.github.salilvnair.auditx.core.aop.annotation.AuditX;
import com.github.salilvnair.auditx.core.aop.context.AuditXContext;
import com.github.salilvnair.auditx.core.aop.context.AuditXPublishContext;
import com.github.salilvnair.auditx.core.aop.interceptor.AuditXContextInterceptor;
import com.github.salilvnair.auditx.core.model.AuditSeverity;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import com.github.salilvnair.auditx.core.service.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Aspect that intercepts @AuditX methods, collects AuditXContext entries
 * accumulated during execution, and publishes a single AuditWriteRequest via AuditService.
 * <p>
 * Nesting:
 *   DEPTH tracks call depth per thread.
 *   Only the outermost @AuditX method publishes and clears.
 *   Inner @AuditX methods only contribute AuditXContext records.
 * <p>
 * conversationId / traceId:
 *   Resolved via SpEL against method arguments if expression is provided on @AuditX.
 *   Example: @AuditX(eventType="ChangeUserRole", conversationId="#request.header.userId", traceId="#request.header.traceId")
 */
// Run outside @Transactional so the business transaction commits/rollbacks before the audit save.
// @Transactional defaults to Ordered.LOWEST_PRECEDENCE (Integer.MAX_VALUE — innermost).
// This aspect at LOWEST_PRECEDENCE - 1 is one step outer, ensuring a clean transaction for audit writes.
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class AuditableAspect {

    private static final Logger logger = LoggerFactory.getLogger(AuditableAspect.class);

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final AuditService auditService;
    private final List<AuditXContextInterceptor> interceptors;
    private final ExpressionParser spel = new SpelExpressionParser();

    public AuditableAspect(AuditService auditService) {
        this(auditService, Collections.emptyList());
    }

    public AuditableAspect(AuditService auditService, List<AuditXContextInterceptor> interceptors) {
        this.auditService = auditService;
        this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
    }

    @Pointcut("@annotation(auditX)")
    public void auditablePointCut(AuditX auditX) {}

    @Around("auditablePointCut(auditX)")
    public Object around(ProceedingJoinPoint pjp, AuditX auditX) throws Throwable {
        boolean isRoot = DEPTH.get() == 0;
        DEPTH.set(DEPTH.get() + 1);
        long start = System.currentTimeMillis();

        if (isRoot) {
            // Track per-iteration start so publish() reports duration of each loop iteration,
            // not duration from method start. Reset after each inline publish.
            final long[] iterationStart = {start};
            AuditXContext.setInlinePublisher(() -> {
                long elapsed = System.currentTimeMillis() - iterationStart[0];
                publish(pjp, auditX, elapsed, null);
                iterationStart[0] = System.currentTimeMillis();
            });
        }

        Throwable error = null;
        try {
            return pjp.proceed();   // ← business code always runs; aspect never swallows it
        }
        catch (Throwable ex) {
            error = ex;
            throw ex;               // ← always rethrown, aspect is transparent to callers
        }
        finally {
            DEPTH.set(DEPTH.get() - 1);
            if (isRoot) {
                try {
                    // Skip final publish if publish() already flushed everything and there is
                    // no error to record. Handles the cron/batch loop pattern cleanly.
                    boolean hasContent = !AuditXContext.isEmpty()
                            || !AuditXContext.tagSnapshot().isEmpty()
                            || !AuditXContext.canonicalSnapshot().isEmpty();
                    if (hasContent || error != null) {
                        long durationMs = System.currentTimeMillis() - start;
                        publish(pjp, auditX, durationMs, error);
                    }
                }
                catch (Throwable publishEx) {
                    // publish failure must NEVER affect the caller — only log
                    logger.warn("AuditableAspect: failed to publish audit event for [{}]: {}",
                            auditX.eventType(), publishEx.getMessage());
                }
                finally {
                    // always clean up thread-locals so pooled threads are not polluted
                    AuditXContext.clear();
                    DEPTH.remove();
                }
            }
        }
    }

    private void publish(ProceedingJoinPoint pjp, AuditX auditX, long durationMs, Throwable error) {
        if (!evaluateCondition(auditX.condition(), pjp)) {
            return;
        }

        Map<String, Object> snapshot    = AuditXContext.snapshot();
        Map<String, String> tagSnapshot = AuditXContext.tagSnapshot();
        // canonical overrides: set via AuditXContext.recordConversationId() etc. inside
        // the method body. They take precedence over @AuditX SpEL expressions.
        Map<String, String> canonical   = AuditXContext.canonicalSnapshot();

        AuditSeverity severity = error != null ? auditX.errorSeverity() : auditX.severity();

        AuditWriteRequest.Builder builder = AuditWriteRequest.builder()
                .eventType(auditX.eventType())
                .source(auditX.source())
                .severity(severity)
                .businessKey("durationMs", durationMs)
                .businessKey("method", pjp.getSignature().toShortString())
                .businessKey("status", error != null ? "ERROR" : "OK");

        if (!snapshot.isEmpty()) {
            builder.extraMap(snapshot);
        }

        if (!tagSnapshot.isEmpty()) {
            builder.tags(tagSnapshot);
        }

        // For each canonical field: context override wins, SpEL is the fallback.
        String conversationId = canonical.containsKey("conversationId")
                ? canonical.get("conversationId")
                : resolveSpEL(auditX.conversationId(), pjp);
        if (conversationId != null) builder.conversationId(conversationId);

        String traceId = canonical.containsKey("traceId")
                ? canonical.get("traceId")
                : resolveSpEL(auditX.traceId(), pjp);
        if (traceId != null) builder.traceId(traceId);

        String groupId = canonical.containsKey("groupId")
                ? canonical.get("groupId")
                : resolveSpEL(auditX.groupId(), pjp);
        if (groupId != null) builder.groupId(groupId);

        String interactionId = canonical.containsKey("interactionId")
                ? canonical.get("interactionId")
                : resolveSpEL(auditX.interactionId(), pjp);
        if (interactionId != null) builder.interactionId(interactionId);

        // sessionId has no @AuditX SpEL equivalent — context-only
        String sessionId = canonical.get("sessionId");
        if (sessionId != null) builder.sessionId(sessionId);

        if (error != null) {
            String msg = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            builder.error("message", msg).error("type", error.getClass().getName());
        }

        AuditXPublishContext ctx = interceptors.isEmpty()
                ? null
                : new AuditXPublishContext(pjp, auditX, durationMs, error, snapshot, tagSnapshot, builder);

        if (ctx != null) {
            for (AuditXContextInterceptor interceptor : interceptors) {
                try {
                    interceptor.intercept(ctx);
                }
                catch (Throwable ex) {
                    logger.warn("AuditableAspect: interceptor [{}] threw during intercept for [{}]: {}",
                            interceptor.getClass().getSimpleName(), auditX.eventType(), ex.getMessage());
                }
            }
        }

        AuditWriteRequest request = builder.build();
        auditService.publish(request);

        if (ctx != null) {
            for (AuditXContextInterceptor interceptor : interceptors) {
                try {
                    interceptor.afterPublish(ctx, request);
                }
                catch (Throwable ex) {
                    logger.warn("AuditableAspect: interceptor [{}] threw during afterPublish for [{}]: {}",
                            interceptor.getClass().getSimpleName(), auditX.eventType(), ex.getMessage());
                }
            }
        }
    }

    private boolean evaluateCondition(String expression, ProceedingJoinPoint pjp) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            String[] paramNames = sig.getParameterNames();
            Object[] args = pjp.getArgs();
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            if (paramNames != null && args != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    if (paramNames[i] != null && i < args.length) {
                        ctx.setVariable(paramNames[i], args[i]);
                    }
                }
            }
            Object result = spel.parseExpression(expression).getValue(ctx);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            // non-boolean result — treat as "condition met"
            return result != null;
        }
        catch (Exception ex) {
            // evaluation failure must never suppress publishing — default to publish
            logger.debug("AuditableAspect: condition SpEL failed for [{}], defaulting to publish: {}",
                    expression, ex.getMessage());
            return true;
        }
    }

    private String resolveSpEL(String expression, ProceedingJoinPoint pjp) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            String[] paramNames = sig.getParameterNames();   // null if compiled without -parameters
            Object[] args = pjp.getArgs();
            if (paramNames == null || paramNames.length == 0 || args == null) {
                return null;
            }
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            for (int i = 0; i < paramNames.length; i++) {
                if (paramNames[i] != null && i < args.length) {
                    ctx.setVariable(paramNames[i], args[i]);
                }
            }
            Object value = spel.parseExpression(expression).getValue(ctx);
            return value != null ? value.toString() : null;
        } catch (Exception ignore) {
            // SpEL failure must never affect the actual method — silently skip
            logger.debug("AuditableAspect: SpEL evaluation skipped for expression [{}]: {}", expression, ignore.getMessage());
        }
        return null;
    }
}
