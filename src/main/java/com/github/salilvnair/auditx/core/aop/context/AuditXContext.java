package com.github.salilvnair.auditx.core.aop.context;

import java.util.*;

/**
 * Thread-local context that accumulates audit facts during a single @AuditX method's execution.
 * <p>
 * Three storage buckets:
 *   CONTEXT   — arbitrary key/value pairs → written to extra_map JSONB column
 *   TAGS      — string-only key/value pairs → written to tags JSONB column (indexed)
 *   CANONICAL — canonical field overrides (conversationId, interactionId, etc.)
 *               → override @AuditX SpEL expressions; last write wins
 * <p>
 * One publish control:
 *   publish() — triggers an immediate audit record publish and resets all three buckets
 *               for the next iteration. Designed for cron/batch loops where each
 *               iteration needs its own audit record.
 * <p>
 * Usage examples:
 * <p>
 *   // Single record
 *   AuditXContext.record("psrCustomer", customer.isPsr());
 * <p>
 *   // Bulk records (alternating key, value, key, value, ...)
 *   AuditXContext.records(
 *       "billRef",       bill.getBillRef(),
 *       "billingEngine", "BILLING_API_V2"
 *   );
 * <p>
 *   // Single tag
 *   AuditXContext.tag("system", "ZAPPER");
 * <p>
 *   // Bulk tags
 *   AuditXContext.tags(
 *       "system",      "ZAPPER",
 *       "meterSerial", serial
 *   );
 * <p>
 *   // Canonical field overrides (prefer over @AuditX SpEL)
 *   AuditXContext.recordConversationId(userId);
 *   AuditXContext.recordInteractionId(userId);
 *   AuditXContext.recordGroupId(batchId);
 *   AuditXContext.recordTraceId(traceId);
 *   AuditXContext.recordSessionId(sessionId);
 * <p>
 *   // Mid-loop publish (cron/batch pattern)
 *   for (UserRequest req : pending) {
 *       AuditXContext.recordInteractionId(req.getUserId());
 *       AuditXContext.record("outcome", "SUCCESS");
 *       AuditXContext.publish(); // publish this iteration, reset for next
 *   }
 * <p>
 * No Spring injection needed. Pure static calls.
 * Works across the entire call chain in the same thread.
 */
public final class AuditXContext {

    // extra_map bucket — arbitrary runtime values
    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            ThreadLocal.withInitial(LinkedHashMap::new);

    // tags bucket — string-only, low-cardinality, indexed JSONB column
    private static final ThreadLocal<Map<String, String>> TAGS =
            ThreadLocal.withInitial(LinkedHashMap::new);

    // canonical overrides — set from inside the method body; override @AuditX SpEL
    private static final ThreadLocal<Map<String, String>> CANONICAL =
            ThreadLocal.withInitial(LinkedHashMap::new);

    // wired by AuditableAspect on entry; called by publish() for mid-loop records
    private static final ThreadLocal<Runnable> INLINE_PUBLISHER = new ThreadLocal<>();

    private AuditXContext() {}

    // ── extra_map record methods ───────────────────────────────────────────── //

    /**
     * Record a single key-value pair into extra_map.
     * Overwrites if the key already exists.
     */
    public static void record(String key, Object value) {
        CONTEXT.get().put(key, value);
    }

    /**
     * Record multiple key-value pairs in one call.
     * Pairs must alternate: key, value, key, value, ...
     * Keys must be Strings; values can be any Object.
     * Throws IllegalArgumentException on odd count — fails loudly (programmer error).
     * <p>
     * Example:
     *   AuditXContext.records(
     *       "billRef",       bill.getBillRef(),
     *       "billingEngine", "BILLING_API_V2"
     *   );
     */
    public static void records(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("records() requires an even number of arguments (key, value, ...)");
        }
        Map<String, Object> ctx = CONTEXT.get();
        for (int i = 0; i < pairs.length; i += 2) {
            ctx.put((String) pairs[i], pairs[i + 1]);
        }
    }

    /**
     * Append a value to a list under the given key.
     * Safe for loops and recursive methods — accumulates values without overwriting.
     * <p>
     * Behaviour:
     *   - Key absent           → creates List containing this value
     *   - Key = plain value    → converts to List [old, new]
     *   - Key = List           → appends to the existing list
     * <p>
     * Example:
     *   AuditXContext.append("visitedNodes", nodeId);
     *   // → { "visitedNodes": ["A", "B", "C"] }
     */
    @SuppressWarnings("unchecked")
    public static void append(String key, Object value) {
        Map<String, Object> ctx = CONTEXT.get();
        Object existing = ctx.get(key);
        if (existing instanceof List) {
            ((List<Object>) existing).add(value);
        }
        else if (existing != null) {
            List<Object> list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            ctx.put(key, list);
        }
        else {
            List<Object> list = new ArrayList<>();
            list.add(value);
            ctx.put(key, list);
        }
    }

    /** Read the full extra_map snapshot. Returns an unmodifiable view. */
    public static Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(CONTEXT.get());
    }

    /** True when nothing has been recorded into extra_map yet. */
    public static boolean isEmpty() {
        return CONTEXT.get().isEmpty();
    }

    // ── tag methods ───────────────────────────────────────────────────────── //

    /**
     * Write a single structured tag — stored in the dedicated tags JSONB column.
     * Use for low-cardinality, indexable metadata: tenant, environment, system, feature-flag.
     * Both key and value must be Strings.
     * <p>
     * Contrast with record(): record() is for arbitrary runtime values.
     * tag() is for stable categorical labels you will filter/index by.
     * <p>
     * Example:
     *   AuditXContext.tag("system", "ZAPPER");
     *   AuditXContext.tag("env",    "production");
     */
    public static void tag(String key, String value) {
        TAGS.get().put(key, value);
    }

    /**
     * Write multiple tags in one call.
     * Pairs must alternate: key, value, key, value, ...
     * Both keys and values must be Strings.
     * Throws IllegalArgumentException on odd count.
     * <p>
     * Example:
     *   AuditXContext.tags(
     *       "system",      "ZAPPER",
     *       "meterSerial", serial != null ? serial : "UNKNOWN"
     *   );
     */
    public static void tags(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("tags() requires an even number of arguments (key, value, ...)");
        }
        Map<String, String> t = TAGS.get();
        for (int i = 0; i < pairs.length; i += 2) {
            t.put((String) pairs[i], (String) pairs[i + 1]);
        }
    }

    /** Read the full tag snapshot. Returns an unmodifiable view. */
    public static Map<String, String> tagSnapshot() {
        return Collections.unmodifiableMap(TAGS.get());
    }

    // ── canonical field overrides ─────────────────────────────────────────── //

    /**
     * Set the conversationId for the audit record from inside the method body.
     * Overrides the conversationId SpEL expression on @AuditX if both are present.
     * Last call wins — safe to call multiple times.
     */
    public static void recordConversationId(String conversationId) {
        CANONICAL.get().put("conversationId", conversationId);
    }

    /**
     * Set the interactionId for the audit record from inside the method body.
     * Overrides the interactionId SpEL expression on @AuditX if both are present.
     * Last call wins.
     * <p>
     * Typical use — cron loop where interactionId = per-item request ID:
     *   AuditXContext.recordInteractionId(req.getUserId());
     */
    public static void recordInteractionId(String interactionId) {
        CANONICAL.get().put("interactionId", interactionId);
    }

    /**
     * Set the groupId for the audit record from inside the method body.
     * Overrides the groupId SpEL expression on @AuditX if both are present.
     * Last call wins.
     */
    public static void recordGroupId(String groupId) {
        CANONICAL.get().put("groupId", groupId);
    }

    /**
     * Set the traceId for the audit record from inside the method body.
     * Overrides the traceId SpEL expression on @AuditX if both are present.
     * Last call wins.
     */
    public static void recordTraceId(String traceId) {
        CANONICAL.get().put("traceId", traceId);
    }

    /**
     * Set the sessionId for the audit record from inside the method body.
     * No SpEL equivalent on @AuditX — this is the only way to set sessionId via context.
     * Last call wins.
     */
    public static void recordSessionId(String sessionId) {
        CANONICAL.get().put("sessionId", sessionId);
    }

    /** Read the full canonical overrides snapshot. Returns an unmodifiable view. */
    public static Map<String, String> canonicalSnapshot() {
        return Collections.unmodifiableMap(CANONICAL.get());
    }

    // ── mid-loop publish ──────────────────────────────────────────────────── //

    /**
     * Publish the current context immediately as an audit record, then reset
     * extra_map, tags, and canonical overrides so the next iteration starts clean.
     * <p>
     * Must be called from within an @AuditX-annotated method (the aspect wires the
     * publisher on entry). Throws IllegalStateException if called outside @AuditX.
     * <p>
     * Designed for cron/batch loops where each iteration needs its own audit record:
     *
     *   @AuditX(eventType = "ZAP_CRON_USER_REQUEST", source = AuditSource.CRON)
     *   public void cronJob() {
     *       for (UserRequest req : pending) {
     *           AuditXContext.recordInteractionId(req.getUserId());
     *           AuditXContext.record("outcome", "SUCCESS");
     *           AuditXContext.publish();   // one record per iteration
     *       }
     *       // method exits → context empty → aspect skips final publish
     *   }
     * <p>
     * If the method exits normally with a non-empty context (i.e. publish() was NOT
     * called, or was called but more records were added after the last call), the
     * aspect publishes once on exit as usual.
     */
    public static void publish() {
        Runnable publisher = INLINE_PUBLISHER.get();
        if (publisher == null) {
            throw new IllegalStateException(
                "AuditXContext.publish() must be called within an @AuditX method. " +
                "Ensure the calling method (or one of its callers) is annotated with @AuditX.");
        }
        publisher.run();
        // Reset all three buckets for the next iteration; INLINE_PUBLISHER stays wired.
        CONTEXT.remove();
        TAGS.remove();
        CANONICAL.remove();
    }

    // ── lifecycle — called by AuditableAspect only ────────────────────────── //

    /**
     * Wires the inline publisher used by publish().
     * Called by AuditableAspect on root method entry — do not call directly.
     */
    public static void setInlinePublisher(Runnable publisher) {
        INLINE_PUBLISHER.set(publisher);
    }

    /**
     * Clears all thread-locals including the inline publisher.
     * Called by AuditableAspect in the outermost method's finally block.
     */
    public static void clear() {
        CONTEXT.remove();
        TAGS.remove();
        CANONICAL.remove();
        INLINE_PUBLISHER.remove();
    }
}
