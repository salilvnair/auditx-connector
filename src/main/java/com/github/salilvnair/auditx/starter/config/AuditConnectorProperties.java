package com.github.salilvnair.auditx.starter.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "audit.connector")
@Getter
@Setter
public class AuditConnectorProperties {
    private boolean enabled = true;
    private boolean enforceIdempotency = true;
    private boolean asyncJpaPublish = true;
    private boolean asyncKafkaPublish = true;
    private AuditPublisherType publisherType = AuditPublisherType.ASYNC_DB;
    private Kafka kafka = new Kafka();
    private OutboxDrain outboxDrain = new OutboxDrain();

    @Getter
    @Setter
    public static class Kafka {
        private String topic = "auditx.events";
        private KafkaMessageKeyType messageKeyType = KafkaMessageKeyType.IDEMPOTENCY_KEY;
    }

    @Getter
    @Setter
    public static class OutboxDrain {
        private boolean enabled = false;
        private String table = "auditx_outbox";
        private int batchSize = 100;
        private int maxBatchesPerCall = 5;
        private int maxRetryDelaySeconds = 300;
        private String workerId = "auditx-outbox-drainer";
    }
}
