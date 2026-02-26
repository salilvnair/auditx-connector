package com.github.salilvnair.auditx.starter.outbox;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class OutboxDrainSummary {
    int batchesProcessed;
    int claimedCount;
    int sentCount;
    int failedCount;
    int deadLetterCount;
    long elapsedMs;
}
