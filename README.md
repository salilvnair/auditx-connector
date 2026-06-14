# AuditX Connector

Reusable Spring Boot audit connector for publishing canonical audit events with provider switch support:
- `ASYNC_DB` provider (persist to PostgreSQL)
- `KAFKA` provider (publish canonical envelope JSON to Kafka)

---

## Changelog

### 1.0.9
- `AuditXContext.recordConversationId(String)` — override `@AuditX` `conversationId` SpEL from inside the method body
- `AuditXContext.recordInteractionId(String)` — override `@AuditX` `interactionId` SpEL from inside the method body
- `AuditXContext.recordGroupId(String)` — override `@AuditX` `groupId` SpEL from inside the method body
- `AuditXContext.recordTraceId(String)` — override `@AuditX` `traceId` SpEL from inside the method body
- `AuditXContext.recordSessionId(String)` — set `sessionId` programmatically (no SpEL equivalent on `@AuditX`)
- `AuditXContext.publish()` — mid-loop publish for cron/batch jobs; fires the audit record immediately, resets context for the next iteration
- `AuditableAspect` skips the final publish when context is empty and no error occurred (supports the loop-publish pattern correctly)

### 1.0.8
- Removed mandatory `sessionId` validation for `source = UI` — `sessionId` is now always optional regardless of source

### 1.0.7
- `AuditXContext.records(Object... pairs)` — bulk record multiple key-value pairs in one call
- `AuditXContext.tags(Object... pairs)` — bulk set multiple tags in one call

### 1.0.6
- `AuditXContext.tag(String key, String value)` — write a single structured tag
- `AuditXContext.tagSnapshot()` — read the tag map (unmodifiable)
- Dedicated `tags` JSONB column in `AUDITX_EVENT` for indexable, low-cardinality metadata

### 1.0.5
- `@AuditX` method-level annotation — zero-boilerplate audit capture on any Spring bean method
- `AuditableAspect` — `@Around` advice with depth tracking, SpEL resolution, transaction-safe ordering
- `AuditXContext` — thread-local context: `record()`, `append()`
- `AuditXPublishContext` — snapshot carrier passed to interceptors
- `AuditXContextInterceptor` — pre/post publish hook interface
- `MdcAuditXInterceptor` — built-in MDC enrichment interceptor

---

## Step-by-Step Usage

### Step 1: Add dependency

Add `auditx-connector` to your consumer service dependencies.

```xml
<dependency>
    <groupId>com.github.salilvnair</groupId>
    <artifactId>auditx-connector</artifactId>
    <version>1.0.9</version>
</dependency>
```

### Step 2: Enable connector

```java
@SpringBootApplication
@EnableAuditX
public class OrderUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderUserApplication.class, args);
    }
}
```

Without `@EnableAuditX`, AuditX beans are not created.

### Step 3: Configure provider in `application.yml`

#### ASYNC_DB mode (default)

```yaml
audit:
  connector:
    enabled: true
    publisher-type: ASYNC_DB
    enforce-idempotency: true
    async-jpa-publish: true
```

#### Kafka mode

```yaml
audit:
  connector:
    enabled: true
    publisher-type: KAFKA
    enforce-idempotency: true
    async-kafka-publish: true
    kafka:
      topic: auditx.events
      message-key-type: IDEMPOTENCY_KEY

spring:
  kafka:
    bootstrap-servers: localhost:9092
```

Kafka key strategies (`audit.connector.kafka.message-key-type`):
- `IDEMPOTENCY_KEY` (default)
- `EVENT_ID`
- `CONVERSATION_ID`

#### Dynamic table mapping (AuditxEntityConfig)

`AuditxPhysicalNamingStrategy` maps logical `AUDITX_EVENT` using:

```yaml
auditx:
  entity:
    tables:
      EVENT: your_custom_audit_table
```

This lets consumers override the physical table name without changing connector code.

#### Outbox drain endpoint config (cron-driven)

```yaml
audit:
  connector:
    outbox-drain:
      enabled: true
      table: auditx_outbox
      batch-size: 200
      max-batches-per-call: 10
      max-retry-delay-seconds: 300
      worker-id: auditx-cron-drainer
```

---

### Step 4: Create DB table

#### Full DDL (fresh install)

```sql
CREATE TABLE IF NOT EXISTS AUDITX_EVENT (
    event_id         UUID         DEFAULT uuid_generate_v4() NOT NULL,
    event_time       TIMESTAMP    NOT NULL,
    event_type       TEXT         NOT NULL,
    severity         TEXT         NOT NULL,
    source           TEXT         NOT NULL,
    service_name     TEXT,
    service_version  TEXT,
    environment      TEXT,
    session_id       TEXT,
    conversation_id  TEXT,
    group_id         TEXT,
    interaction_id   TEXT,
    trace_id         TEXT,
    span_id          TEXT,
    idempotency_key  TEXT         NOT NULL,
    business_keys    JSONB,
    extra_map        JSONB,
    tags             JSONB,
    actor            JSONB,
    error_map        JSONB,
    event_payload    JSONB,
    CONSTRAINT AUDITX_EVENT_pkey PRIMARY KEY (event_id),
    CONSTRAINT uk_auditx_event_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_auditx_event_time           ON AUDITX_EVENT (event_time);
CREATE INDEX IF NOT EXISTS idx_auditx_event_type           ON AUDITX_EVENT (event_type);
CREATE INDEX IF NOT EXISTS idx_auditx_event_group_id       ON AUDITX_EVENT (group_id);
CREATE INDEX IF NOT EXISTS idx_auditx_event_interaction_id ON AUDITX_EVENT (interaction_id);
CREATE INDEX IF NOT EXISTS idx_auditx_event_source_time    ON AUDITX_EVENT (source, event_time);
CREATE INDEX IF NOT EXISTS idx_auditx_event_conv_id        ON AUDITX_EVENT (conversation_id);
-- GIN index for JSONB tag queries (e.g. WHERE tags @> '{"system":"ZAPPER"}')
CREATE INDEX IF NOT EXISTS idx_auditx_event_tags           ON AUDITX_EVENT USING GIN (tags);
```

#### Upgrade DDL (existing installations on ≤ 1.0.4)

If you are upgrading from a version before 1.0.5, run the following ALTER statements to add the `tags` column and the new indexes:

```sql
-- Add tags column (1.0.5)
ALTER TABLE AUDITX_EVENT
    ADD COLUMN IF NOT EXISTS tags JSONB;

-- Add conversation_id index (1.0.5)
CREATE INDEX IF NOT EXISTS idx_auditx_event_conv_id
    ON AUDITX_EVENT (conversation_id);

-- Add GIN index for JSONB tag queries (1.0.6)
CREATE INDEX IF NOT EXISTS idx_auditx_event_tags
    ON AUDITX_EVENT USING GIN (tags);
```

---

### Step 5: Annotate methods with `@AuditX`

`@AuditX` is the zero-boilerplate way to capture audit events at the method level.
Place it on any Spring-managed bean method. The aspect intercepts execution, collects everything
you drop into `AuditXContext` during the call, and publishes one audit record on method exit.

#### Annotation fields

| Field | Type | Default | Description |
|---|---|---|---|
| `eventType` | `String` | *(required)* | Event type written to the audit record |
| `source` | `AuditSource` | `SYSTEM` | Originating actor: `API`, `SYSTEM`, `CRON`, `UI`, `KAFKA`, etc. |
| `severity` | `AuditSeverity` | `INFO` | Severity on successful completion |
| `errorSeverity` | `AuditSeverity` | `ERROR` | Severity when the method throws |
| `conversationId` | SpEL `String` | `""` | Correlation ID — resolved against method args |
| `traceId` | SpEL `String` | `""` | Distributed trace ID — resolved against method args |
| `groupId` | SpEL `String` | `""` | Group ID — resolved against method args |
| `interactionId` | SpEL `String` | `""` | Interaction ID — resolved against method args |
| `condition` | SpEL `String` | `""` | Gate expression — `false` skips publish; method still runs |

#### Basic example

```java
@AuditX(
    eventType      = "ZAP_SUBMIT_USER_REQUEST",
    source         = AuditSource.API,
    conversationId = "#request.userId"
)
public UserResult submitUser(SubmitUserRequest request) {
    AuditXContext.record("accountNumber", request.getAccountNumber());
    AuditXContext.record("bundleCode",    request.getBundleCode());
    // ... business logic
    return result;
}
```

#### SpEL on nested fields

```java
@AuditX(
    eventType      = "USER_ROLE_CHANGED",
    source         = AuditSource.UI,
    conversationId = "#request.header.userId",
    traceId        = "#request.header.traceId",
    groupId        = "#request.header.groupId"
)
public void changeUserRole(ChangeUserRoleRequest request) { ... }
```

#### Conditional publish — skip audit when a flag is off

The method always runs. Only the publish is suppressed when `condition` evaluates to `false`.

```java
@AuditX(
    eventType = "ZAP_STATUS_CHECK",
    source    = AuditSource.API,
    condition = "#auditEnabled == true"
)
public StatusResult getStatus(String userId, boolean auditEnabled) { ... }
```

#### Error severity

When the method throws, `errorSeverity` is used instead of `severity`.
Both paths write an audit record — the error path includes `error_map` with the exception message and type.

```java
@AuditX(
    eventType     = "PAYMENT_PROCESS",
    source        = AuditSource.SYSTEM,
    severity      = AuditSeverity.INFO,
    errorSeverity = AuditSeverity.CRITICAL
)
public void processPayment(String ref) { ... }
```

#### Nesting — only the outermost method publishes

When `@AuditX` methods call other `@AuditX` methods, only the outermost one publishes.
Inner methods still contribute `AuditXContext` records — they merge into the root record.
No inner records are written separately.

```java
// root — DEPTH=0 on entry → publishes ZAP_SUBMIT_USER_REQUEST
@AuditX(eventType = "ZAP_SUBMIT_USER_REQUEST", source = AuditSource.API)
public UserResult submitUser(SubmitUserRequest request) {
    List<String> errors = validationService.validate(request);  // ← nested @AuditX
    // validationService.validate writes valCustomerFound, valErrorCount, etc.
    // Those keys merge INTO this record — no separate ZAP_VALIDATE_SUBMIT is written.
}

// nested — DEPTH=1 on entry → contributes only, does not publish
@AuditX(eventType = "ZAP_VALIDATE_SUBMIT", source = AuditSource.API)
public List<String> validate(SubmitUserRequest request) {
    AuditXContext.record("valCustomerFound", customer.isPresent());
    AuditXContext.record("valErrorCount",    errors.size());
}
```

Result in DB: one row for `ZAP_SUBMIT_USER_REQUEST` containing both root and nested keys.

#### Transaction ordering

`AuditableAspect` runs at `@Order(Ordered.LOWEST_PRECEDENCE - 1)` — one step **outside** `@Transactional`
(which runs at `Ordered.LOWEST_PRECEDENCE`). This means:

1. Aspect opens its `@Around` wrapper
2. `@Transactional` opens a DB transaction
3. Business code runs and commits
4. `@Transactional` closes and commits the transaction
5. Aspect `finally` block runs and publishes the audit record

The business transaction is never extended by audit work. Audit publish always runs, even if the business transaction rolled back.

---

### Step 6: Use `AuditXContext` to attach facts

`AuditXContext` is a thread-local store. Call its static methods anywhere in your call chain during an `@AuditX`-annotated method. No injection needed.

---

#### `record(String key, Object value)` — single key-value pair

Overwrites if the key already exists.
Use for flags, computed values, decision outcomes.

```java
AuditXContext.record("psrCustomer",    customer.isPsr());
AuditXContext.record("debtHold",       debtHold);
AuditXContext.record("assignedReqId",  reqId);
```

---

#### `records(Object... pairs)` — bulk key-value pairs *(1.0.7)*

Alternating `key, value, key, value, ...` — same semantics as repeated `record()` calls.
Keys must be `String`. Values can be any `Object`.
Throws `IllegalArgumentException` if an odd count is passed (programmer error, not swallowed by the aspect).

```java
AuditXContext.records(
    "billRef",           bill.getBillRef(),
    "finalMeterReading", reading.toPlainString(),
    "estimatedAmount",   amount.toPlainString(),
    "billingEngine",     "BILLING_API_V2"
);
```

---

#### `tag(String key, String value)` — single structured tag *(1.0.6)*

Tags go into a dedicated `tags` JSONB column (not `extra_map`).
Use for low-cardinality, indexable metadata you will filter by: tenant, environment, system, feature flag.
Both key and value are `String`.

```java
AuditXContext.tag("system",   "ZAPPER");
AuditXContext.tag("env",      "production");
AuditXContext.tag("tenant",   tenantId);
```

Query by tag in SQL:
```sql
SELECT * FROM auditx_event
WHERE tags @> '{"system": "ZAPPER"}'::jsonb;
```

---

#### `tags(Object... pairs)` — bulk structured tags *(1.0.7)*

Same alternating key-value convention as `records()`, but stored in the `tags` column.
Both keys and values must be `String` — cast fails at runtime if non-String is passed.

```java
AuditXContext.tags(
    "system",      "ZAPPER",
    "meterSerial", meterSerial != null ? meterSerial : "UNKNOWN",
    "region",      "UK-NORTH"
);
```

---

#### `append(String key, Object value)` — accumulate values under one key

Safe for loops and recursion. Turns a key into a list automatically.

| Prior state | After `append("k", v)` |
|---|---|
| Key absent | `{ "k": [v] }` |
| Key = plain value `old` | `{ "k": [old, v] }` |
| Key = list | `{ "k": [..., v] }` |

```java
for (String nodeId : graph.bfs(rootId)) {
    AuditXContext.append("visitedNodes", nodeId);
    // result: { "visitedNodes": ["A", "B", "C", "D"] }
}
```

```java
for (ValidationRule rule : rules) {
    if (!rule.passes(request)) {
        AuditXContext.append("failedRules", rule.code());
    }
}
// result: { "failedRules": ["BALANCE_CHECK", "DATE_WINDOW"] }
```

---

#### Canonical field setters *(1.0.9)* — `recordConversationId`, `recordInteractionId`, `recordGroupId`, `recordTraceId`, `recordSessionId`

Override the `@AuditX` SpEL expressions for canonical fields from inside the method body.
Useful when the value is only available at runtime — e.g. a DB row's ID, a cron-generated batch key — and cannot be referenced via SpEL at annotation-declaration time.
Last call wins. Canonical setter values take precedence over matching `@AuditX` SpEL.

| Method | Overrides |
|---|---|
| `AuditXContext.recordConversationId(String)` | `@AuditX(conversationId = "...")` |
| `AuditXContext.recordInteractionId(String)` | `@AuditX(interactionId = "...")` |
| `AuditXContext.recordGroupId(String)` | `@AuditX(groupId = "...")` |
| `AuditXContext.recordTraceId(String)` | `@AuditX(traceId = "...")` |
| `AuditXContext.recordSessionId(String)` | *(no SpEL equivalent — only way to set sessionId via context)* |

```java
@AuditX(eventType = "ZAP_CRON_PROCESS_ITEM", source = AuditSource.CRON)
public void processOneRequest(String userId, String accountNumber) {
    // userId comes from the DB row — not known at annotation time, can't use SpEL here
    AuditXContext.recordInteractionId(userId);
    AuditXContext.recordConversationId(userId);
    AuditXContext.recordGroupId("CRON-BATCH-" + Instant.now().toEpochMilli() / 60_000);
    AuditXContext.tag("system", "ZAPPER");
    AuditXContext.records("accountNumber", accountNumber, "processingMode", "PER_ITEM");
    // ... business logic
    AuditXContext.record("outcome", "SUCCESS");
}
```

---

#### `publish()` — mid-loop publish *(1.0.9)*

Publish the current context immediately as an audit record, then reset `extra_map`, `tags`, and canonical overrides for the next iteration.
Designed for **cron/batch jobs** where each processed item needs its own row in `auditx_event`.

Must be called from within an `@AuditX`-annotated method — throws `IllegalStateException` otherwise.

When the method exits normally with an **empty** context (all iterations called `publish()`), the aspect **skips** the final publish. If the method throws after partial iterations, the failing iteration's context is captured with the error in the final publish.

**Pattern A — `@AuditX` on the inner per-item method**

Each call to `processOneRequest()` is depth=0 → publishes its own record independently.
Simplest approach. No `publish()` needed.

```java
// Outer orchestrator — no @AuditX
public List<String> runBatch() {
    List<UserRequestEntity> pending = repo.findByStatusNotIn(List.of(900, 950, 999));
    for (UserRequestEntity req : pending) {
        processOneRequest(req.getId(), req.getAccountNumber());  // each call = its own record
    }
}

// Per-item method carries its own @AuditX
@AuditX(eventType = "ZAP_CRON_PROCESS_ITEM", source = AuditSource.CRON)
public void processOneRequest(String userId, String accountNumber) {
    AuditXContext.recordInteractionId(userId);
    AuditXContext.recordGroupId("CRON-" + Instant.now().toEpochMilli() / 60_000);
    AuditXContext.records("accountNumber", accountNumber);
    // ... business logic
    AuditXContext.record("outcome", "SUCCESS");
}
```

**Pattern B — `@AuditX` on the outer method + `publish()` in the loop**

One `@AuditX` wraps the whole batch method. `publish()` fires at the end of each iteration to flush that item's context and reset for the next one.

```java
@AuditX(eventType = "ZAP_CRON_BATCH_ITEM", source = AuditSource.CRON)
public List<String> processPendingBatch(String batchId) {
    List<UserRequestEntity> pending = repo.findByStatusNotIn(List.of(900, 950, 999));
    List<String> processed = new ArrayList<>();

    for (UserRequestEntity req : pending) {
        // canonical fields — per-item, reset by publish() each iteration
        AuditXContext.recordInteractionId(req.getId());
        AuditXContext.recordGroupId(batchId);  // ties all rows to this batch run

        AuditXContext.tag("system", "ZAPPER");
        AuditXContext.records(
            "accountNumber",  req.getAccountNumber(),
            "processingMode", "PATTERN_B_INLINE_PUBLISH",
            "batchId",        batchId
        );

        try {
            doWork(req.getId());
            AuditXContext.record("outcome", "SUCCESS");
            processed.add(req.getId());
        } catch (Exception ex) {
            AuditXContext.record("outcome",       "FAILED");
            AuditXContext.record("failureReason", ex.getMessage());
        }

        AuditXContext.publish(); // flush this item → reset → next iteration starts clean
    }

    // method exits with empty context → aspect skips final publish → no duplicate record
    return processed;
}
```

Result in DB: one row per loop iteration, each with its own `interaction_id`, grouped by `group_id = batchId`.

---

#### Full example combining all methods

```java
@AuditX(
    eventType      = "ZAP_FINAL_BILL",
    source         = AuditSource.SYSTEM,
    conversationId = "#userId"
)
@Transactional
public UserResult triggerFinalBill(String userId) {
    UserRequestEntity req = requestRepository.findById(userId).orElseThrow();
    CustomerEntity customer     = customerRepository.findByAccountNumber(req.getAccountNumber()).orElseThrow();

    FinalBillResult bill = billingService.trigger(userId, req.getAccountNumber(),
                                                   customer.getMeterSerial(), req.getUserDate());

    // tags — go into dedicated indexed column
    AuditXContext.tags(
        "system",      "ZAPPER",
        "meterSerial", customer.getMeterSerial() != null ? customer.getMeterSerial() : "UNKNOWN"
    );

    // records — go into extra_map
    AuditXContext.records(
        "billRef",           bill.getBillRef(),
        "finalMeterReading", bill.getFinalMeterReading().toPlainString(),
        "estimatedAmount",   bill.getEstimatedAmount().toPlainString(),
        "billingEngine",     "BILLING_API_V2"
    );

    // single record added conditionally
    if (customer.isSmartMeter()) {
        AuditXContext.record("smartMeterReading", "REMOTE");
    }

    return UserResult.finalBillTriggered(userId, bill.getBillRef());
}
```

Resulting `auditx_event` row:

```json
{
  "event_type": "ZAP_FINAL_BILL",
  "conversation_id": "f3a1...",
  "tags": {
    "system": "ZAPPER",
    "meterSerial": "MTR-10003"
  },
  "extra_map": {
    "billRef": "BILL-20260611-001",
    "finalMeterReading": "18432.50",
    "estimatedAmount": "127.40",
    "billingEngine": "BILLING_API_V2",
    "smartMeterReading": "REMOTE",
    "durationMs": 43,
    "method": "triggerFinalBill(..)",
    "status": "OK"
  }
}
```

---

### AuditXContextInterceptor — pre/post publish hooks

Implement `AuditXContextInterceptor` to enrich or react to audit events without touching service code.

```java
@Component
public class ServiceMetaInterceptor implements AuditXContextInterceptor {

    @Override
    public void intercept(AuditXPublishContext ctx) {
        // runs synchronously before publish — can mutate the builder
        ctx.builder()
           .actor("service",        "zapper-demo")
           .actor("serviceVersion", "1.0.0");
    }

    @Override
    public void afterPublish(AuditXPublishContext ctx, AuditWriteRequest published) {
        // runs after publish — safe for async side-effects
        log.info("[AUDIT] {} | status={} | duration={}ms | conversationId={}",
                 published.getEventType(),
                 ctx.error() == null ? "OK" : "ERROR",
                 ctx.durationMs(),
                 published.getConversationId());
    }
}
```

`intercept()` must be synchronous — it mutates the builder before the record is written.
`afterPublish()` can fire async work internally (notifications, metrics, MDC cleanup).

---

### Step 7: Publish events directly (imperative API)

If you prefer direct service calls over the `@AuditX` aspect, all three publish styles are supported.

#### Option A: Simple metadata map

```java
auditService.publish(
    "USER_REQUEST_REQUEST_RECEIVED",
    "550e8400-e29b-41d4-a716-446655440000",
    Map.of(
        "zapperCustId", "ZP-10091",
        "plan",         "PREMIUM"
    )
);
```

#### Option B: Full `AuditWriteRequest` builder

```java
auditService.publishError(AuditWriteRequest.builder()
    .eventType("VALIDATION_FAILED")
    .source(AuditSource.CRON)
    .conversationId("550e8400-e29b-41d4-a716-446655440000")
    .groupId("grp-1001")
    .interactionId("int-2001")
    .businessKey("zapperCustId", "ZP-10091")
    .extra("phase",   "ai-validation")
    .extra("model",   "address-similarity")
    .extra("score",   0.72)
    .error("code",    "ADDRESS_MISMATCH")
    .error("message", "Address similarity below threshold")
    .build());
```

#### Option C: Full `CanonicalAuditEnvelope` builder

```java
CanonicalAuditEnvelope envelope = CanonicalAuditEnvelope.builder()
    .eventType("USER_REQUEST_API_TRIGGERED")
    .severity(AuditSeverity.INFO)
    .source(AuditSource.API)
    .serviceName("zapper-user-request-service")
    .serviceVersion("1.2.0")
    .environment("prod")
    .conversationId("550e8400-e29b-41d4-a716-446655440000")
    .groupId("grp-1001")
    .interactionId("int-2001")
    .traceId("trace-9f8d2")
    .spanId("span-11")
    .businessKey("zapperCustId",  "ZP-10091")
    .businessKey("requestType",   "USER_REQUEST")
    .extra("finalDecision", "AUTO_USER_REQUEST_ELIGIBLE")
    .extra("duesStatus",    "CLEAR")
    .actorEntry("initiator", "CCTEAM")
    .build();

auditService.publish(envelope);
```

#### Stage-driven signatures

```java
void publish(AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata);
void publish(AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, AuditWriteRequest baseRequest);
void publish(AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, CanonicalAuditEnvelope baseEnvelope);
```

---

## Utility Helper Example

```java
public enum UserStage implements AuditStage {
    USER_REQUEST_REQUEST_RECEIVED("USER_REQUEST_REQUEST_RECEIVED", AuditSource.EMAIL_POSTFIX, AuditSeverity.INFO),
    BILLING_VALIDATION_FAILED  ("BILLING_VALIDATION_FAILED",   AuditSource.API,           AuditSeverity.ERROR),
    USER_REQUEST_API_TRIGGERED   ("USER_REQUEST_API_TRIGGERED",    AuditSource.API,           AuditSeverity.INFO);

    private final String stageName;
    private final AuditSource source;
    private final AuditSeverity severity;

    UserStage(String stageName, AuditSource source, AuditSeverity severity) {
        this.stageName = stageName; this.source = source; this.severity = severity;
    }

    @Override public String stageName()      { return stageName; }
    @Override public AuditSource source()    { return source; }
    @Override public AuditSeverity severity(){ return severity; }
}
```

---

## Java Audit Ingress API (for cross-language publish)

AuditX exposes a REST endpoint in Spring Boot apps:

- `POST /auditx/v1/events/publish`

Accepts all three payload styles:

**1. stage + metadata map**
```json
{
  "stage": "USER_REQUEST_REQUEST_RECEIVED",
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "traceId": "trace-2001",
  "source": "CRON",
  "severity": "INFO",
  "metadata": { "zapperCustId": "ZP-10091", "plan": "PREMIUM" }
}
```

**2. auditWriteRequest**
```json
{
  "auditWriteRequest": {
    "eventType": "BILLING_VALIDATION_FAILED",
    "source": "API",
    "severity": "ERROR",
    "conversationId": "550e8400-e29b-41d4-a716-446655440000",
    "errorMap": { "code": "ADDRESS_MISMATCH" }
  }
}
```

**3. canonicalEnvelope**
```json
{
  "canonicalEnvelope": {
    "eventType": "USER_REQUEST_API_TRIGGERED",
    "source": "API",
    "conversationId": "550e8400-e29b-41d4-a716-446655440000",
    "extraMap": { "decision": "AUTO_USER_REQUEST_ELIGIBLE" }
  }
}
```

---

## Outbox drain endpoint

If your PostgreSQL function writes rows to `auditx_outbox` using `auditx_enqueue(...)`, call this endpoint from cron:

- `POST /auditx/v1/outbox/drain`

```json
{ "maxBatches": 10, "batchSize": 200 }
```

Behavior:
- Claims pending rows via `FOR UPDATE SKIP LOCKED`
- Publishes each row via `AuditService`
- Marks row `SENT` on success; applies exponential backoff on failure
- Moves to `DEAD_LETTER` after `max_retries`

```bash
curl -s -X POST http://localhost:8080/auditx/v1/outbox/drain \
  -H "Content-Type: application/json" \
  -d '{"maxBatches":10,"batchSize":200}'
```

---

## Idempotency

If caller does not pass `idempotencyKey`, AuditX generates SHA-256 from:

`eventType | source | conversationId | interactionId | groupId`

- `ASYNC_DB` mode: deduplication enforced by unique constraint on `idempotency_key`.
- Kafka mode: same key used as Kafka message key (default strategy).

---

## Validation Rules

- `conversationId` is mandatory and must be a valid UUID.
- `sessionId` is optional for all sources including `UI` (validation removed in 1.0.8).

---

## Optional Event Enum Contract

```java
public enum MyAuditEvents implements AuditEventType {
    USER_REQUEST_REQUEST_RECEIVED,
    BILLING_VALIDATION_FAILED,
    USER_REQUEST_API_TRIGGERED,
    HARD_USER_REQUEST_DONE;

    @Override
    public String code() { return name(); }
}
```
