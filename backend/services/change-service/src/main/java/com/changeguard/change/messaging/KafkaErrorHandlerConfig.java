package com.changeguard.change.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoveryProperties.class)
public class KafkaErrorHandlerConfig {

    /**
     * Exhaust transient retries before quarantine. Recovery must succeed before RECORD acknowledgment;
     * a failed DLT send re-seeks the source record instead of discarding it.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(RawDeadLetterRecoverer recoverer, RecoveryProperties recovery,
            org.springframework.beans.factory.ObjectProvider<org.springframework.kafka.listener.RetryListener> observers) {
        var handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(recovery.retryInterval().toMillis(), recovery.retries()));
        handler.addNotRetryableExceptions(com.changeguard.change.exception.EventProcessingException.class,
                IllegalArgumentException.class, org.springframework.dao.DataIntegrityViolationException.class,
                org.springframework.dao.InvalidDataAccessApiUsageException.class,
                org.springframework.dao.InvalidDataAccessResourceUsageException.class);
        // Observe actual delivery and recovery outcomes, including failures before the listener is invoked.
        handler.setRetryListeners(observers.orderedStream().toArray(org.springframework.kafka.listener.RetryListener[]::new));
        return handler;
    }

    @Bean
    public ProducerFactory<byte[], byte[]> producerFactory(KafkaProperties properties) {
        var settings = new java.util.HashMap<>(properties.buildProducerProperties());
        // Inherit TLS/SASL settings, but make quarantine durability and byte preservation mandatory.
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        settings.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        settings.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        return new DefaultKafkaProducerFactory<>(settings);
    }

    @Bean
    public KafkaTemplate<byte[], byte[]> kafkaTemplate(ProducerFactory<byte[], byte[]> producerFactory) {
        var template = new KafkaTemplate<>(producerFactory);
        var listener = new LoggingProducerListener<byte[], byte[]>();
        listener.setIncludeContents(false);
        template.setProducerListener(listener);
        return template;
    }

    @Bean
    public RawDeadLetterRecoverer deadLetterRecoverer(KafkaTemplate<byte[], byte[]> template,
            RecoveryProperties recovery, org.springframework.core.env.Environment environment) {
        // A source/DLT collision creates an endless poison-record loop; reject it at startup.
        org.springframework.util.Assert.isTrue(!recovery.deadLetterTopic().equals(
                environment.getRequiredProperty("changeguard.kafka.topics.code-events")), "DLT must differ from source topic");
        return new RawDeadLetterRecoverer(template, recovery.deadLetterTopic());
    }
}
