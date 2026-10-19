package com.changeguard.change.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlerConfig {

    /**
     * Stop on failure and retain the failed record's offset for replay.
     * Repair invalid facts or the database, then restart; this release does not discard records into a DLT.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new CommonContainerStoppingErrorHandler();
    }
}
