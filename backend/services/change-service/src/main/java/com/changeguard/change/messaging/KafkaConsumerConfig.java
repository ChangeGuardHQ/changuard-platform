package com.changeguard.change.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

/** Specific Avro transport configured from Boot properties and the shared schema catalog. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
public class KafkaConsumerConfig {

    @Bean
    public ConsumerFactory<Object, Object> consumerFactory(KafkaProperties properties,
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meters) {
        // Avro 1.12.2 permits only the reviewed generated records and their common types.
        com.changeguard.events.CodeEventSchemas.trustGeneratedClasses();
        var factory = new DefaultKafkaConsumerFactory<Object, Object>(properties.buildConsumerProperties());
        // Bind native fetch/lag metrics and remove them when a consumer closes or restarts.
        meters.ifAvailable(registry -> factory.addListener(new org.springframework.kafka.core.MicrometerConsumerListener<>(registry)));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        // Retain Boot's listener settings and the shared CommonErrorHandler bean.
        configurer.configure(factory, consumerFactory);
        return factory;
    }
}
