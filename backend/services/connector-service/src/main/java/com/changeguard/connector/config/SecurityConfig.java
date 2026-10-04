package com.changeguard.connector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.DispatcherType;

/**
 * Configures the HTTP security rules for the connector service.
 *
 * <p>
 * The service uses stateless security and allows GitHub webhook traffic to
 * bypass CSRF validation after signature verification, while all other
 * endpoints require authentication.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /**
     * Defines the filter chain for incoming HTTP requests.
     *
     * <p>
     * This keeps the service stateless and enables Basic authentication for the
     * initial phase, with the GitHub webhook endpoint deliberately exposed only
     * after webhook signature checks are performed by the application layer.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher githubWebhook = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/api/v1/integrations/github/webhook");

        return http
                // Webhook authenticity must be checked with X-Hub-Signature-256 before processing.
                .csrf(csrf -> csrf.ignoringRequestMatchers(githubWebhook))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Use Basic authentication for the initial service; replace with OAuth2/JWT later.
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(githubWebhook).permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .anyRequest().authenticated())
                .build();
    }
}
