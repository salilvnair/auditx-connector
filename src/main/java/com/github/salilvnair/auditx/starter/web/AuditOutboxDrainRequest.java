package com.github.salilvnair.auditx.starter.web;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuditOutboxDrainRequest {
    private Integer maxBatches;
    private Integer batchSize;
}
