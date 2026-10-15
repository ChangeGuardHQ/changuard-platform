package com.changeguard.change.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlerConfig {

    /**
     * Stop on failure and retain the failed record's offset for replay.
     * Retry and dead-letter policies will be defined with the business consumer.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new CommonContainerStoppingErrorHandler();
    }
}
