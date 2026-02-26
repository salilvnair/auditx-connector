package com.github.salilvnair.auditx.starter.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.auditx.core.model.AuditSeverity;
import com.github.salilvnair.auditx.core.model.AuditSource;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;
import com.github.salilvnair.auditx.core.service.AuditService;
import com.github.salilvnair.auditx.starter.config.AuditConnectorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Slf4j
public class AuditOutboxDrainService {
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final AuditConnectorProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OutboxDrainSummary drain(Integer maxBatchesOverride, Integer batchSizeOverride) {
        long start = System.currentTimeMillis();

        int maxBatches = positiveOrDefault(maxBatchesOverride, properties.getOutboxDrain().getMaxBatchesPerCall());
        int batchSize = positiveOrDefault(batchSizeOverride, properties.getOutboxDrain().getBatchSize());

        int batchesProcessed = 0;
        int claimedCount = 0;
        int sentCount = 0;
        int failedCount = 0;
        int deadLetterCount = 0;

        for (int batchNo = 0; batchNo < maxBatches; batchNo++) {
            List<OutboxRecord> records = claimPendingBatch(batchSize);
            if (records.isEmpty()) {
                break;
            }

            batchesProcessed++;
            claimedCount += records.size();

            for (OutboxRecord record : records) {
                try {
                    publishRecord(record);
                    markSent(record.getId());
                    sentCount++;
                } catch (Exception ex) {
                    failedCount++;
                    boolean deadLetter = markFailed(record, ex.getMessage());
                    if (deadLetter) {
                        deadLetterCount++;
                    }
                    log.error("Failed to drain audit outbox row id={}", record.getId(), ex);
                }
            }
        }

        return OutboxDrainSummary.builder()
                .batchesProcessed(batchesProcessed)
                .claimedCount(claimedCount)
                .sentCount(sentCount)
                .failedCount(failedCount)
                .deadLetterCount(deadLetterCount)
                .elapsedMs(System.currentTimeMillis() - start)
                .build();
    }

    private List<OutboxRecord> claimPendingBatch(int batchSize) {
        String table = validateTableName(properties.getOutboxDrain().getTable());

        String sql = """
                WITH picked AS (
                    SELECT id
                    FROM %s
                    WHERE status = 'PENDING'
                      AND next_retry_at <= now()
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE %s o
                SET status = 'PROCESSING',
                    worker_id = ?,
                    locked_at = now(),
                    updated_at = now()
                FROM picked
                WHERE o.id = picked.id
                RETURNING o.*
                """.formatted(table, table);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> mapRecord(rs),
                batchSize,
                properties.getOutboxDrain().getWorkerId()
        );
    }

    private void publishRecord(OutboxRecord record) {
        if (!record.getCanonicalEnvelope().isEmpty()) {
            CanonicalAuditEnvelope envelope = objectMapper.convertValue(record.getCanonicalEnvelope(), CanonicalAuditEnvelope.class);
            auditService.publish(envelope);
            return;
        }

        if (!record.getAuditWriteRequest().isEmpty()) {
            AuditWriteRequest request = objectMapper.convertValue(record.getAuditWriteRequest(), AuditWriteRequest.class);
            auditService.publish(request);
            return;
        }

        AuditWriteRequest.Builder builder = AuditWriteRequest.builder()
                .eventType(record.getStage())
                .conversationId(record.getConversationId())
                .traceId(record.getTraceId())
                .source(resolveSource(record.getSource()))
                .severity(resolveSeverity(record.getSeverity()));

        if (!record.getMetadata().isEmpty()) {
            builder.extraMap(record.getMetadata());
        }

        auditService.publish(builder.build());
    }

    private void markSent(long id) {
        String table = validateTableName(properties.getOutboxDrain().getTable());
        String sql = """
                UPDATE %s
                SET status = 'SENT',
                    processed_at = now(),
                    last_error = NULL,
                    updated_at = now()
                WHERE id = ?
                """.formatted(table);
        jdbcTemplate.update(sql, id);
    }

    private boolean markFailed(OutboxRecord record, String errorMessage) {
        int nextRetryCount = record.getRetryCount() + 1;
        int maxRetries = record.getMaxRetries() > 0 ? record.getMaxRetries() : 5;
        boolean deadLetter = nextRetryCount >= maxRetries;
        String nextStatus = deadLetter ? "DEAD_LETTER" : "PENDING";
        int delaySeconds = Math.min((int) Math.pow(2, nextRetryCount), properties.getOutboxDrain().getMaxRetryDelaySeconds());

        String table = validateTableName(properties.getOutboxDrain().getTable());
        String sql = """
                UPDATE %s
                SET status = ?,
                    retry_count = ?,
                    next_retry_at = now() + (? * interval '1 second'),
                    last_error = ?,
                    updated_at = now()
                WHERE id = ?
                """.formatted(table);

        jdbcTemplate.update(sql, nextStatus, nextRetryCount, delaySeconds, trim(errorMessage, 2000), record.getId());
        return deadLetter;
    }

    private OutboxRecord mapRecord(ResultSet rs) throws SQLException {
        return OutboxRecord.builder()
                .id(rs.getLong("id"))
                .stage(rs.getString("stage"))
                .conversationId(rs.getString("conversation_id"))
                .traceId(rs.getString("trace_id"))
                .source(rs.getString("source"))
                .severity(rs.getString("severity"))
                .metadata(parseJsonMap(rs.getString("metadata")))
                .auditWriteRequest(parseJsonMap(rs.getString("audit_write_request")))
                .canonicalEnvelope(parseJsonMap(rs.getString("canonical_envelope")))
                .retryCount(rs.getInt("retry_count"))
                .maxRetries(rs.getInt("max_retries"))
                .build();
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid outbox JSON payload", ex);
        }
    }

    private AuditSource resolveSource(String source) {
        if (source == null || source.isBlank()) {
            return AuditSource.OTHER;
        }

        try {
            return AuditSource.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AuditSource.OTHER;
        }
    }

    private AuditSeverity resolveSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return AuditSeverity.INFO;
        }

        try {
            return AuditSeverity.valueOf(severity.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AuditSeverity.INFO;
        }
    }

    private String validateTableName(String tableName) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid outbox table name: " + tableName);
        }
        return tableName;
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return value;
    }

    private String trim(String value, int maxLen) {
        if (value == null) {
            return null;
        }

        if (value.length() <= maxLen) {
            return value;
        }

        return value.substring(0, maxLen);
    }
}
