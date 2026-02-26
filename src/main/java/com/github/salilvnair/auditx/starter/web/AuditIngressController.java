package com.github.salilvnair.auditx.starter.web;

import com.github.salilvnair.auditx.core.model.AuditSeverity;
import com.github.salilvnair.auditx.core.model.AuditSource;
import com.github.salilvnair.auditx.core.model.AuditWriteRequest;
import com.github.salilvnair.auditx.core.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/auditx/v1/events")
@RequiredArgsConstructor
public class AuditIngressController {
    private final AuditService auditService;

    @PostMapping("/publish")
    public Map<String, String> publish(@RequestBody AuditPublishApiRequest request) {
        if (request.getCanonicalEnvelope() != null) {
            auditService.publish(request.getCanonicalEnvelope());
            return ok("canonicalEnvelope");
        }

        if (request.getAuditWriteRequest() != null) {
            auditService.publish(request.getAuditWriteRequest());
            return ok("auditWriteRequest");
        }

        if (isBlank(request.getStage()) || isBlank(request.getConversationId())) {
            throw new IllegalArgumentException("Either canonicalEnvelope/auditWriteRequest OR stage+conversationId is required");
        }

        AuditWriteRequest.Builder builder = AuditWriteRequest.builder()
                .eventType(request.getStage())
                .conversationId(request.getConversationId())
                .traceId(request.getTraceId())
                .source(request.getSource() == null ? AuditSource.OTHER : request.getSource())
                .severity(request.getSeverity() == null ? AuditSeverity.INFO : request.getSeverity());

        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            builder.extraMap(request.getMetadata());
        }

        auditService.publish(builder.build());
        return ok("stage+metadata");
    }

    private Map<String, String> ok(String mode) {
        return Collections.unmodifiableMap(Map.of("status", "ACCEPTED", "mode", mode));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
