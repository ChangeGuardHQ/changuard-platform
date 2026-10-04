package com.changeguard.connector.github;

/**
 * Handles incoming GitHub webhook events delivered to the connector service.
 */
public interface GitHubWebhookService {

    /**
     * Processes a GitHub webhook event.
     *
     * @param eventType the GitHub event type, such as push or pull_request
     * @param deliveryId the unique delivery identifier for the webhook request
     * @param signature the HMAC signature included in the request headers
     * @param payload the raw webhook payload bytes
     */
    void handleWebhook(
            String eventType,
            String deliveryId,
            String signature,
            byte[] payload
    );
}
