package com.github.salilvnair.auditx.starter.web;

import com.github.salilvnair.auditx.starter.outbox.AuditOutboxDrainService;
import com.github.salilvnair.auditx.starter.outbox.OutboxDrainSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auditx/v1/outbox")
@RequiredArgsConstructor
public class AuditOutboxDrainController {
    private final AuditOutboxDrainService outboxDrainService;

    @PostMapping("/drain")
    public OutboxDrainSummary drain(@RequestBody(required = false) AuditOutboxDrainRequest request) {
        Integer maxBatches = request == null ? null : request.getMaxBatches();
        Integer batchSize = request == null ? null : request.getBatchSize();
        return outboxDrainService.drain(maxBatches, batchSize);
    }
}
