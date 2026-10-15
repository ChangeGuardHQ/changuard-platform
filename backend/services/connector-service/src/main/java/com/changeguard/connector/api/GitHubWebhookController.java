package com.changeguard.connector.api;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.changeguard.connector.github.GitHubWebhookService;

@RestController
@RequestMapping("/api/v1/integrations/github")
/**
 * Receives GitHub webhook deliveries for connector integrations.
 *
 * <p>
 * The endpoint validates the required GitHub headers before delegating the raw
 * payload to the backing webhook service, which is responsible for verifying
 * the HMAC signature and processing the event payload.</p>
 */
public class GitHubWebhookController {

    /**
     * GitHub's webhook signature format used for validating the HMAC value.
     */
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("sha256=[0-9a-fA-F]{64}");

    private final GitHubWebhookService webhookService;

    /**
     * Creates the controller with the active webhook
     * implementation.
     *
     * @param webhookService the durable webhook processor
     */
    public GitHubWebhookController(GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Accepts a GitHub delivery and validates that the required headers and
     * signature are present before handing the raw payload to the
     * implementation-specific service.
     *
     * @param eventType GitHub event type header
     * @param deliveryId unique delivery identifier header
     * @param signature optional HMAC signature header
     * @param payload raw webhook payload body
     * @return HTTP 202 Accepted when the delivery is accepted for processing
     * @throws ResponseStatusException 400 when required headers are missing,
     * 401 when the signature is invalid, 403 for an unconnected repository,
     * or 503 when durable persistence is unavailable
     */
    @PostMapping(path = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receiveWebhook(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-GitHub-Delivery") String deliveryId,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] payload) {
        if (eventType.isBlank() || deliveryId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook event and delivery ID are required");
        }
        if (signature == null || !SIGNATURE_PATTERN.matcher(signature).matches()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or malformed webhook signature");
        }

        // Preserve the exact bytes: the service must verify the HMAC before parsing this body.
        webhookService.handleWebhook(eventType, deliveryId, signature, payload);
        return ResponseEntity.accepted().build();
    }
}
