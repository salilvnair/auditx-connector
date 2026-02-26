package com.github.salilvnair.auditx.core.service;

import com.github.salilvnair.auditx.core.model.CanonicalAuditEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Generates deterministic idempotency keys to deduplicate retries/duplicate callbacks.
 */
public class DefaultIdempotencyKeyFactory implements IdempotencyKeyFactory {
    @Override
    public String create(CanonicalAuditEnvelope envelope) {
        String input = String.join("|",
                safe(envelope.getEventType()),
                safe(envelope.getSource() == null ? null : envelope.getSource().name()),
                safe(envelope.getConversationId()),
                safe(envelope.getInteractionId()),
                safe(envelope.getGroupId())
        );
        return sha256(input);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }
}
