# AuditX Connector

Reusable Spring Boot audit connector for publishing canonical audit events with provider switch support:
- `ASYNC_DB` provider (persist to PostgreSQL)
- `KAFKA` provider (publish canonical envelope JSON to Kafka)

## Step-by-Step Usage

## Step 1: Add dependency

Add `auditx-connector` to your consumer service dependencies.

## Step 2: Enable connector

```java
@SpringBootApplication
@EnableAuditX
public class OrderDisconnectApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderDisconnectApplication.class, args);
    }
}
```

Without `@EnableAuditX`, AuditX beans are not created.

## Step 3: Configure provider in `application.yml`

### ASYNC_DB mode (default)

```yaml
audit:
  connector:
    enabled: true
    publisher-type: ASYNC_DB
    enforce-idempotency: true
    async-jpa-publish: true
```

### Kafka mode

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

### Dynamic table mapping (AuditxEntityConfig)

`AuditxPhysicalNamingStrategy` maps logical `AUDITX_EVENT` using:

```yaml
auditx:
  entity:
    tables:
      EVENT: your_custom_audit_table
```

This lets consumers override the physical table name without changing connector code.
If your app uses a composite naming strategy (for example via `ccf-core`), this strategy is discovered as a regular `PhysicalNamingStrategy` bean and can be composed there.

### Outbox drain endpoint config (cron-driven)

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

## Step 4: If using ASYNC_DB, create DB table manually

```sql
CREATE TABLE IF NOT EXISTS AUDITX_EVENT (
    event_id TEXT PRIMARY KEY,
    event_time TIMESTAMP NOT NULL,
    event_type TEXT NOT NULL,
    severity TEXT NOT NULL,
    source TEXT NOT NULL,
    service_name TEXT,
    service_version TEXT,
    environment TEXT,
    session_id TEXT,
    conversation_id TEXT,
    group_id TEXT,
    interaction_id TEXT,
    trace_id TEXT,
    span_id TEXT,
    idempotency_key TEXT NOT NULL,
    business_keys JSONB,
    extra_map JSONB,
    actor JSONB,
    error_map JSONB,
    event_payload JSONB,
    CONSTRAINT uk_auditx_event_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_auditx_event_time ON AUDITX_EVENT (event_time);
CREATE INDEX IF NOT EXISTS idx_auditx_event_type ON AUDITX_EVENT (event_type);
CREATE INDEX IF NOT EXISTS idx_auditx_event_group_id ON AUDITX_EVENT (group_id);
CREATE INDEX IF NOT EXISTS idx_auditx_event_interaction_id ON AUDITX_EVENT (interaction_id);
CREATE INDEX IF NOT EXISTS idx_auditx_event_source_time ON AUDITX_EVENT (source, event_time);
```

## Step 5: Publish events

### Option A: Recommended simple API (`AuditWriteRequest` behind the scenes)

```java
Map<String, Object> metadata = new HashMap<>();
metadata.put("zapperCustId", "ZP-10091");
metadata.put("address", "ABCD, XX");
metadata.put("plan", "PREMIUM");
metadata.put("sourceSystem", "CCTEAM_EXCEL");
metadata.put("validationStatus", "PENDING");

auditService.publish(
        "DISCONNECT_REQUEST_RECEIVED",
        "550e8400-e29b-41d4-a716-446655440000",
        metadata
);
```

### Option B: Full `AuditWriteRequest` builder

```java
auditService.publishError(AuditWriteRequest.builder()
        .eventType("VALIDATION_FAILED")
        .source(AuditSource.CRON)
        .conversationId("550e8400-e29b-41d4-a716-446655440000")
        .groupId("grp-1001")
        .interactionId("int-2001")
        .businessKey("zapperCustId", "ZP-10091")
        .extra("phase", "ai-validation")
        .extra("model", "address-similarity")
        .extra("score", 0.72)
        .error("code", "ADDRESS_MISMATCH")
        .error("message", "Address similarity below threshold")
        .build());
```

### Option C: Full `CanonicalAuditEnvelope` builder (advanced)

Use this when you need direct control over every canonical field.

```java
CanonicalAuditEnvelope envelope = CanonicalAuditEnvelope.builder()
        .eventType("DISCONNECT_API_TRIGGERED")
        .severity(AuditSeverity.INFO)
        .source(AuditSource.API)
        .serviceName("zapper-disconnect-service")
        .serviceVersion("1.2.0")
        .environment("prod")
        .conversationId("550e8400-e29b-41d4-a716-446655440000")
        .groupId("grp-1001")
        .interactionId("int-2001")
        .traceId("trace-9f8d2")
        .spanId("span-11")
        .businessKey("zapperCustId", "ZP-10091")
        .businessKey("requestType", "DISCONNECT")
        .extra("finalDecision", "AUTO_DISCONNECT_ELIGIBLE")
        .extra("duesStatus", "CLEAR")
        .actorEntry("initiator", "CCTEAM")
        .build();

auditService.publish(envelope);
```

## Utility Helper Example (Stage enum + both builders + metadata map)

```java
package com.example.audit;

import com.github.salilvnair.auditx.core.model.AuditSeverity;
import com.github.salilvnair.auditx.core.model.AuditSource;
import com.github.salilvnair.auditx.core.model.AuditStage;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;
import com.github.salilvnair.auditx.core.service.AuditPublisher;
import com.github.salilvnair.auditx.core.service.AuditService;

import java.util.HashMap;
import java.util.Map;

public final class AuditEventUtil {

    private AuditEventUtil() {
    }

    public enum DisconnectStage implements AuditStage {
        DISCONNECT_REQUEST_RECEIVED("DISCONNECT_REQUEST_RECEIVED", AuditSource.EMAIL_POSTFIX, AuditSeverity.INFO),
        INVENTORY_ENRICHMENT_STARTED("INVENTORY_ENRICHMENT_STARTED", AuditSource.CRON, AuditSeverity.INFO),
        BILLING_VALIDATION_FAILED("BILLING_VALIDATION_FAILED", AuditSource.API, AuditSeverity.ERROR),
        DISCONNECT_API_TRIGGERED("DISCONNECT_API_TRIGGERED", AuditSource.API, AuditSeverity.INFO);

        private final String stageName;
        private final AuditSource source;
        private final AuditSeverity severity;

        DisconnectStage(String stageName, AuditSource source, AuditSeverity severity) {
            this.stageName = stageName;
            this.source = source;
            this.severity = severity;
        }

        @Override
        public String stageName() {
            return stageName;
        }

        @Override
        public AuditSource source() {
            return source;
        }

        @Override
        public AuditSeverity severity() {
            return severity;
        }
    }

    public static Map<String, Object> baseMetadata(String zapperCustId, String address, String plan) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("zapperCustId", zapperCustId);
        metadata.put("address", address);
        metadata.put("plan", plan);
        metadata.put("domain", "ELECTRICITY_DISCONNECT");
        return metadata;
    }

    public static void publishByStage(
            AuditService auditService,
            DisconnectStage stage,
            String conversationId,
            String traceId,
            Map<String, Object> metadata
    ) {
        auditService.publish(stage, conversationId, traceId, metadata);
    }

    public static void publishByStageWithBaseRequest(
            AuditService auditService,
            DisconnectStage stage,
            String conversationId,
            String traceId,
            Map<String, Object> metadata,
            AuditWriteRequest baseRequest
    ) {
        auditService.publish(stage, conversationId, traceId, metadata, baseRequest);
    }

    public static void publishByStageWithBaseEnvelope(
            AuditService auditService,
            DisconnectStage stage,
            String conversationId,
            String traceId,
            Map<String, Object> metadata,
            CanonicalAuditEnvelope baseEnvelope
    ) {
        auditService.publish(stage, conversationId, traceId, metadata, baseEnvelope);
    }

    public static AuditWriteRequest buildWriteRequest(
            String stage,
            String conversationId,
            String groupId,
            String interactionId,
            Map<String, Object> metadata
    ) {
        AuditWriteRequest.Builder builder = AuditWriteRequest.builder()
                .eventType(stage)
                .source(AuditSource.CRON)
                .severity(AuditSeverity.INFO)
                .conversationId(conversationId)
                .groupId(groupId)
                .interactionId(interactionId);

        if (metadata != null) {
            builder.extraMap(metadata);
        }

        return builder.build();
    }

    public static CanonicalAuditEnvelope buildCanonicalEnvelope(
            String stage,
            String conversationId,
            String groupId,
            String interactionId,
            Map<String, Object> metadata
    ) {
        CanonicalAuditEnvelope.Builder builder = CanonicalAuditEnvelope.builder()
                .eventType(stage)
                .source(AuditSource.SYSTEM)
                .severity(AuditSeverity.INFO)
                .serviceName("zapper-disconnect-service")
                .environment("prod")
                .conversationId(conversationId)
                .groupId(groupId)
                .interactionId(interactionId)
                .businessKey("entityType", "disconnect-request");

        if (metadata != null) {
            builder.extraMap(metadata);
        }

        return builder.build();
    }

    public static void publishWithWriteRequest(AuditService auditService, AuditWriteRequest request) {
        auditService.publish(request);
    }

    public static void publishWithCanonical(AuditPublisher auditPublisher, CanonicalAuditEnvelope envelope) {
        auditPublisher.publish(envelope);
    }
}
```

`AuditService` now supports these stage-driven utility signatures:

```java
void publish(AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata);
void publish(AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, AuditWriteRequest baseRequest);
void publish(AuditStage stage, String conversationId, String traceId, Map<String, Object> metadata, CanonicalAuditEnvelope baseEnvelope);
```

## Validation Rules

- `conversationId` is mandatory and must be a valid UUID.
- If `source = UI`, `sessionId` is mandatory.

## Java Audit Ingress API (for cross-language publish)

AuditX now exposes a REST endpoint in Spring Boot apps:

- `POST /auditx/v1/events/publish`

This endpoint accepts all 3 payload styles:

1. `stage + metadata map`

```json
{
  "stage": "DISCONNECT_REQUEST_RECEIVED",
  "conversationId": "550e8400-e29b-41d4-a716-446655440000",
  "traceId": "trace-2001",
  "source": "CRON",
  "severity": "INFO",
  "metadata": {
    "zapperCustId": "ZP-10091",
    "plan": "PREMIUM"
  }
}
```

2. `auditWriteRequest`

```json
{
  "auditWriteRequest": {
    "eventType": "BILLING_VALIDATION_FAILED",
    "source": "API",
    "severity": "ERROR",
    "conversationId": "550e8400-e29b-41d4-a716-446655440000",
    "groupId": "grp-1001",
    "interactionId": "int-2001",
    "traceId": "trace-2001",
    "errorMap": {
      "code": "ADDRESS_MISMATCH"
    }
  }
}
```

3. `canonicalEnvelope`

```json
{
  "canonicalEnvelope": {
    "eventType": "DISCONNECT_API_TRIGGERED",
    "source": "API",
    "severity": "INFO",
    "conversationId": "550e8400-e29b-41d4-a716-446655440000",
    "groupId": "grp-1001",
    "interactionId": "int-2001",
    "traceId": "trace-2001",
    "extraMap": {
      "decision": "AUTO_DISCONNECT_ELIGIBLE"
    }
  }
}
```

## Outbox drain endpoint (for SQL function outbox)

If your PostgreSQL function writes rows to `auditx_outbox` using `auditx_enqueue(...)`, call this endpoint from cron:

- `POST /auditx/v1/outbox/drain`

Optional request body:

```json
{
  "maxBatches": 10,
  "batchSize": 200
}
```

Behavior:
- Claims pending rows using `FOR UPDATE SKIP LOCKED`
- Publishes each row via `AuditService`
- Marks row `SENT` on success
- On failure applies exponential backoff and retries
- Moves to `DEAD_LETTER` after `max_retries`

Example cron call:

```bash
curl -s -X POST http://localhost:8080/auditx/v1/outbox/drain \\
  -H \"Content-Type: application/json\" \\
  -d '{\"maxBatches\":10,\"batchSize\":200}'
```

## Idempotency

If caller does not pass `idempotencyKey`, AuditX generates SHA-256 from:

`eventType | source | conversationId | interactionId | groupId`

- In ASYNC_DB mode: dedupe enforced by unique key on `idempotency_key`.
- In Kafka mode: same idempotency value can be used as Kafka key (default).

## Example Stage Names for Disconnect Flow

- `DISCONNECT_REQUEST_RECEIVED`
- `INVENTORY_ENRICHMENT_STARTED`
- `INVENTORY_ENRICHMENT_COMPLETED`
- `BILLING_VALIDATION_STARTED`
- `BILLING_VALIDATION_FAILED`
- `ADDRESS_VALIDATION_PASSED`
- `AUTO_DISCONNECT_ELIGIBLE`
- `DISCONNECT_API_TRIGGERED`
- `SOFT_DISCONNECT_DONE`
- `HARD_DISCONNECT_DONE`
- `ASYNC_STATUS_CALLBACK_RECEIVED`
- `CUSTOMER_EMAIL_SENT`

## Optional Event Enum Contract

```java
public enum MyAuditEvents implements AuditEventType {
    DISCONNECT_REQUEST_RECEIVED,
    INVENTORY_ENRICHMENT_COMPLETED,
    BILLING_VALIDATION_FAILED,
    DISCONNECT_API_TRIGGERED,
    HARD_DISCONNECT_DONE;

    @Override
    public String code() {
        return name();
    }
}
```
