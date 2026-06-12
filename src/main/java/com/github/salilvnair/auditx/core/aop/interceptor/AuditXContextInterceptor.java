package com.github.salilvnair.auditx.core.aop.interceptor;

import com.github.salilvnair.auditx.core.aop.context.AuditXPublishContext;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;

/**
 * Hook called by AuditableAspect around auditService.publish().
 *
 * Two hook points:
 *   intercept()    — called BEFORE publish; use context.getBuilder() to enrich the request.
 *   afterPublish() — called AFTER publish succeeds; use for metrics, local logging, side-effects.
 *                    Not called if publish() throws.
 *
 * Still @FunctionalInterface (one abstract method) so lambda registration works for intercept-only use.
 * Override afterPublish() when you need post-publish behaviour.
 *
 * Control execution order across multiple interceptors via {@code @Order} on the bean declaration:
 * <pre>{@code
 *   @Bean @Order(1) public AuditXContextInterceptor securityInterceptor() { ... }
 *   @Bean @Order(2) public AuditXContextInterceptor metricsInterceptor() { ... }
 * }</pre>
 *
 * Example — enrich before publish:
 * <pre>{@code
 *   @Bean
 *   public AuditXContextInterceptor securityAuditInterceptor() {
 *       return ctx -> {
 *           String principal = SecurityContextHolder.getContext()
 *               .getAuthentication().getName();
 *           ctx.getBuilder().actorEntry("userId", principal);
 *       };
 *   }
 * }</pre>
 */
@FunctionalInterface
public interface AuditXContextInterceptor {

    /**
     * Called after the builder is fully populated and before auditService.publish().
     * Use context.getBuilder() to add or override fields on the request.
     */
    void intercept(AuditXPublishContext context);

    /**
     * Called after auditService.publish() completes successfully.
     * Override for post-publish side-effects: metrics counters, local audit logs, alerts.
     * Default is a no-op.
     *
     * @param context   the same context passed to intercept()
     * @param published the final AuditWriteRequest that was published
     */
    default void afterPublish(AuditXPublishContext context, AuditWriteRequest published) {}
}
