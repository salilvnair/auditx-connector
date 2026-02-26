package com.github.salilvnair.auditx.starter.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.auditx.core.service.AuditPublisher;
import com.github.salilvnair.auditx.core.service.AuditService;
import com.github.salilvnair.auditx.core.service.DefaultIdempotencyKeyFactory;
import com.github.salilvnair.auditx.core.service.IdempotencyKeyFactory;
import com.github.salilvnair.auditx.starter.config.AuditConnectorProperties;
import com.github.salilvnair.auditx.starter.outbox.AuditOutboxDrainService;
import com.github.salilvnair.auditx.starter.persistence.AuditEventRepository;
import com.github.salilvnair.auditx.starter.provider.KafkaAuditPublisher;
import com.github.salilvnair.auditx.starter.provider.JpaAuditPublisher;
import com.github.salilvnair.auditx.starter.service.DefaultAuditService;
import com.github.salilvnair.auditx.starter.web.AuditIngressController;
import com.github.salilvnair.auditx.starter.web.AuditOutboxDrainController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.web.bind.annotation.RestController;

@AutoConfiguration
@ConditionalOnClass(AuditPublisher.class)
@EnableConfigurationProperties(AuditConnectorProperties.class)
public class AuditConnectorAutoConfiguration {

    @Configuration
    @Conditional(AsyncDbPublisherCondition.class)
    @EnableJpaRepositories(basePackageClasses = AuditEventRepository.class)
    static class JpaRepositoryConfiguration {
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyKeyFactory idempotencyKeyFactory() {
        return new DefaultIdempotencyKeyFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(AsyncDbPublisherCondition.class)
    public AuditPublisher jpaAuditPublisher(
            AuditEventRepository repository,
            IdempotencyKeyFactory idempotencyKeyFactory,
            AuditConnectorProperties properties,
            AsyncTaskExecutor auditXAsyncTaskExecutor
    ) {
        return new JpaAuditPublisher(repository, idempotencyKeyFactory, properties, auditXAsyncTaskExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit.connector", name = "publisher-type", havingValue = "KAFKA")
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnBean(KafkaTemplate.class)
    public AuditPublisher kafkaAuditPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            IdempotencyKeyFactory idempotencyKeyFactory,
            AuditConnectorProperties properties,
            AsyncTaskExecutor auditXAsyncTaskExecutor
    ) {
        return new KafkaAuditPublisher(
                kafkaTemplate,
                idempotencyKeyFactory,
                properties,
                auditXAsyncTaskExecutor,
                new ObjectMapper()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncTaskExecutor auditXAsyncTaskExecutor() {
        return new SimpleAsyncTaskExecutor("auditx-jpa-publisher-");
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditService auditService(AuditPublisher auditPublisher) {
        return new DefaultAuditService(auditPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RestController.class)
    public AuditIngressController auditIngressController(AuditService auditService) {
        return new AuditIngressController(auditService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "audit.connector.outbox-drain", name = "enabled", havingValue = "true")
    @ConditionalOnBean(JdbcTemplate.class)
    public AuditOutboxDrainService auditOutboxDrainService(
            JdbcTemplate jdbcTemplate,
            AuditService auditService,
            AuditConnectorProperties properties
    ) {
        return new AuditOutboxDrainService(jdbcTemplate, auditService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RestController.class)
    @ConditionalOnProperty(prefix = "audit.connector.outbox-drain", name = "enabled", havingValue = "true")
    public AuditOutboxDrainController auditOutboxDrainController(AuditOutboxDrainService outboxDrainService) {
        return new AuditOutboxDrainController(outboxDrainService);
    }
}
