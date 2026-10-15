package com.changeguard.connector.github.connection;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubConnectionProperties.class)
public class GitHubConnectionConfiguration {
    @Bean
    GitHubProvider gitHubProvider(GitHubConnectionProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(5));
        return new GitHubAppClient(properties, RestClient.builder().requestFactory(requests), Clock.systemUTC());
    }
}
