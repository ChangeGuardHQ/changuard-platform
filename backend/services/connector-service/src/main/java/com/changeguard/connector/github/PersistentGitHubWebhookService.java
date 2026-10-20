package com.changeguard.connector.github;

import java.time.Instant;

import com.changeguard.connector.event.PullRequestMergedEvent;
import com.changeguard.connector.normalization.GitHubEventNormalizer;
import com.changeguard.connector.persistence.ConnectorIntegrationRepository;
import com.changeguard.connector.persistence.ConnectorIntakeStore;
import com.changeguard.connector.persistence.GitHubLifecycleStore;
import com.changeguard.connector.persistence.RepositoryNotConnectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists validated GitHub webhook deliveries after authenticating the request
 * and normalizing the event.
 * <p>
 * Installation lifecycle deliveries persist a receipt and scoped access changes. Code
 * deliveries persist normalized domain events only when the GitHub
 * installation is active and the repository is connected. Both paths commit
 * durable storage before acknowledging the request.
 */
@Service
public class PersistentGitHubWebhookService implements GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PersistentGitHubWebhookService.class);
    private final GitHubSignatureValidator signatures;
    private final GitHubEventNormalizer normalizer;
    private final ConnectorIntegrationRepository integrations;
    private final ConnectorIntakeStore intake;
    private final GitHubLifecycleNormalizer lifecycleNormalizer;
    private final GitHubLifecycleStore lifecycleStore;
    private final com.changeguard.connector.normalization.GitHubCodeLifecycleNormalizer codeLifecycle;
    private final boolean codeLifecycleEnabled;

    public PersistentGitHubWebhookService(GitHubSignatureValidator signatures, GitHubEventNormalizer normalizer,
            ConnectorIntegrationRepository integrations, ConnectorIntakeStore intake,
            GitHubLifecycleNormalizer lifecycleNormalizer, GitHubLifecycleStore lifecycleStore,
            com.changeguard.connector.normalization.GitHubCodeLifecycleNormalizer codeLifecycle,
            @org.springframework.beans.factory.annotation.Value("${changeguard.github.code-lifecycle-enabled:false}") boolean codeLifecycleEnabled) {
        this.signatures = signatures;
        this.normalizer = normalizer;
        this.integrations = integrations;
        this.intake = intake;
        this.lifecycleNormalizer = lifecycleNormalizer;
        this.lifecycleStore = lifecycleStore;
        this.codeLifecycle = codeLifecycle;
        this.codeLifecycleEnabled = codeLifecycleEnabled;
    }

    @Override
    public void handleWebhook(String eventType, String deliveryId, String signature, byte[] payload) {
        Instant receivedAt = Instant.now();
        if (!signatures.isValid(payload, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }
        try {
            var lifecycle = lifecycleNormalizer.normalize(eventType, payload);
            if (lifecycle.isPresent()) {
                lifecycleStore.accept(lifecycle.get(), deliveryId, receivedAt);
                return;
            }
            // Roll out the consumer first, then enable the additional producer contracts explicitly.
            if (codeLifecycleEnabled) {
                var code = codeLifecycle.normalize(eventType, deliveryId, payload);
                if (code.isPresent()) {
                    var integration = integrations.findActiveGitHubInstallation(code.get().installationId())
                            .orElseThrow(RepositoryNotConnectedException::new);
                    intake.acceptLifecycle(code.get().events(integration.organizationId(), integration.integrationId(), receivedAt),
                            code.get().repositoryOwnerId());
                    return;
                }
            }
            var delivery = normalizer.normalizeDelivery(eventType, deliveryId, payload);
            if (delivery.isEmpty()) {
                return; // Verified unsupported activity is deliberately ignored.
            }
            var integration = integrations.findActiveGitHubInstallation(delivery.get().installationId())
                    .orElseThrow(RepositoryNotConnectedException::new);
            var event = PullRequestMergedEvent.fromGitHub(delivery.get().pullRequest(),
                    integration.organizationId(), integration.integrationId(), receivedAt);
            // The store checks and locks the active selected repository in the same transaction.
            intake.accept(event, delivery.get().repositoryOwnerId());
        } catch (InvalidGitHubPayloadException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GitHub webhook payload");
        } catch (RepositoryNotConnectedException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitHub installation or repository is not connected");
        } catch (DataAccessException | TransactionException exception) {
            // Do not log provider-controlled bodies, delivery IDs, secrets or database error contents.
            log.warn("Webhook persistence unavailable ({})", exception.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook persistence is unavailable");
        }
    }
}
