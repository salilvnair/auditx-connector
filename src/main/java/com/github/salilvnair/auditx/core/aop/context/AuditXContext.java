package com.github.salilvnair.auditx.core.aop.context;

import java.util.*;

/**
 * Thread-local context that accumulates key-value audit facts (branch variables,
 * decision flags, computed values) during a single method's execution.

 * Designed to work with @AuditX and AuditableAspect.
 * After the outermost @AuditX method exits, the aspect reads the snapshot,
 * publishes it via AuditService, and clears the context.

 * Usage — drop anywhere in your service/workflow/handler:

 *   AuditXContext.record("adminUser", adminUser);
 *   AuditXContext.record("testApproved", testApproved);
 *   AuditXContext.record("callbackPath", "failure");
 * <p>
 *   AuditXContext.tag("tenant", tenantId);
 *   AuditXContext.tag("env", "production");
 *   AuditXContext.tag("featureFlag", "new-pricing-enabled");
 * <p>
 *   AuditXContext.append("visitedNode", nodeId); // for recursive/looping methods
 * <p>
 *   AuditXContext.records(
 *       "billRef",           billRef,
 *       "finalMeterReading", reading.toPlainString(),
 *       "billingEngine",     "BILLING_API_V2"
 *   );
 * <p>
 *   AuditXContext.tags(
 *       "system",      "ZAPPER",
 *       "meterSerial", meterSerial != null ? meterSerial : "UNKNOWN"
 *   );

 * No Spring injection needed. Pure static calls.
 * Works across the entire call chain in the same thread.
 */
public final class AuditXContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            ThreadLocal.withInitial(LinkedHashMap::new);

    // Tags are string-only values — used for structured, low-cardinality metadata
    // (environment, tenant, region, feature-flag) that goes into its own DB column
    // so it can be indexed and filtered without touching extraMap.
    private static final ThreadLocal<Map<String, String>> TAGS =
            ThreadLocal.withInitial(LinkedHashMap::new);

    private AuditXContext() {}

    /**
     * Record a single key-value pair.
     * Overwrites if the key already exists.
     * Use for flags and one-time values: adminUser, testApproved, callbackPath.
     */
    public static void record(String key, Object value) {
        CONTEXT.get().put(key, value);
    }

    /**
     * Record a structured tag — a string key/value pair stored in a dedicated tags column.
     * Use for low-cardinality, indexable metadata: tenant, environment, region, feature-flag.
     * These go into their own DB column (not extraMap), so they can be filtered efficiently.
     * <p>
     * Contrast with record(): record() is for arbitrary runtime values (booleans, counts,
     * computed objects). tag() is for stable, categorical labels you will query by.
     * <p>
     * Example:
     *   AuditXContext.tag("tenant", tenantId);
     *   AuditXContext.tag("env", "production");
     *   AuditXContext.tag("featureFlag", "new-pricing-enabled");
     */
    public static void tag(String key, String value) {
        TAGS.get().put(key, value);
    }

    /** Read the full tag snapshot. Returns an unmodifiable view. */
    public static Map<String, String> tagSnapshot() {
        return Collections.unmodifiableMap(TAGS.get());
    }

    /**
     * Append a value to a list under the given key.
     * Safe for recursive or looping methods where the same key is written many times.
     * <p>
     * Behaviour:
     *   - Key does not exist yet          → creates List with this value
     *   - Key exists as a plain value     → converts to List, keeps old value, adds new
     *   - Key exists as a List            → appends to the existing list
     * <p>
     * Example in a recursive method:
     *   AuditXContext.append("visitedNode", nodeId);
     * Snapshot result:
     *   { "visitedNode": [1, 4, 7, 3] }
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

    /**
     * Record multiple key-value pairs in one call.
     * Pairs must alternate: key, value, key, value, ...
     * Keys must be Strings; values can be any Object.
     * <p>
     * Example:
     *   AuditXContext.records(
     *       "billRef",           billRef,
     *       "finalMeterReading", reading.toPlainString(),
     *       "billingEngine",     "BILLING_API_V2"
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
     * Record multiple tags in one call.
     * Pairs must alternate: key, value, key, value, ...
     * Both keys and values must be Strings.
     * <p>
     * Example:
     *   AuditXContext.tags(
     *       "system",      "ZAPPER",
     *       "meterSerial", meterSerial != null ? meterSerial : "UNKNOWN"
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

    /** Read the full snapshot. Returns an unmodifiable view. */
    public static Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(CONTEXT.get());
    }

    /** True when nothing has been recorded yet (does not check tags). */
    public static boolean isEmpty() {
        return CONTEXT.get().isEmpty();
    }

    /** Clear both the context and tags. Called by AuditableAspect when the outermost method exits. */
    public static void clear() {
        CONTEXT.remove();
        TAGS.remove();
    }
}