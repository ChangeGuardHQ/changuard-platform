package com.changeguard.connector.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {
}
