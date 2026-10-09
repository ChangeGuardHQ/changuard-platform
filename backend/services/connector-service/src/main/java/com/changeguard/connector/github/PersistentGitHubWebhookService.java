package com.changeguard.connector.github;

import java.time.Instant;

import com.changeguard.connector.event.PullRequestMergedEvent;
import com.changeguard.connector.normalization.GitHubEventNormalizer;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import com.changeguard.connector.persistence.ConnectorIntakeStore;
import com.changeguard.connector.persistence.RepositoryNotConnectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.server.ResponseStatusException;

/** Authenticates raw deliveries and commits owned merge events before HTTP acknowledgement. */
@Service
public class PersistentGitHubWebhookService implements GitHubWebhookService {
    private static final Logger log = LoggerFactory.getLogger(PersistentGitHubWebhookService.class);
    private final GitHubSignatureValidator signatures;
    private final GitHubEventNormalizer normalizer;
    private final ConnectorIntegrationRepository integrations;
    private final ConnectorIntakeStore intake;

    public PersistentGitHubWebhookService(GitHubSignatureValidator signatures, GitHubEventNormalizer normalizer,
            ConnectorIntegrationRepository integrations, ConnectorIntakeStore intake) {
        this.signatures = signatures;
        this.normalizer = normalizer;
        this.integrations = integrations;
        this.intake = intake;
    }

    @Override
    public void handleWebhook(String eventType, String deliveryId, String signature, byte[] payload) {
        Instant receivedAt = Instant.now();
        if (!signatures.isValid(payload, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        try {
            var delivery = normalizer.normalizeDelivery(eventType, deliveryId, payload);
            if (delivery.isEmpty()) {
                return; // Verified unsupported activity is deliberately ignored.
            }
            var integration = integrations.findActiveGitHubInstallation(delivery.get().installationId())
                    .orElseThrow(RepositoryNotConnectedException::new);
            var event = PullRequestMergedEvent.fromGitHub(delivery.get().pullRequest(),
                    integration.organizationId(), integration.integrationId(), receivedAt);
            // The store checks and locks the active selected repository in the same transaction.
            intake.accept(event);
        } catch (InvalidGitHubPayloadException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GitHub pull request payload");
        } catch (RepositoryNotConnectedException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitHub installation or repository is not connected");
        } catch (DataAccessException | TransactionException exception) {
            // Do not log provider-controlled bodies, delivery IDs, secrets or database error contents.
            log.warn("Webhook persistence unavailable ({})", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook persistence is unavailable");
        }
    }
}
