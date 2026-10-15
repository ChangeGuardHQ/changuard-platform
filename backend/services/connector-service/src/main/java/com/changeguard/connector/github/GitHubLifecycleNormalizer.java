package com.changeguard.connector.github;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Normalizes GitHub installation lifecycle webhook payloads after exact-byte
 * HMAC verification.
 * <p>
 * Supported events are installation and installation_repositories. This
 * component only accepts the lifecycle actions that affect connector state, and
 * it converts the raw JSON into a small immutable delivery model for downstream
 * processing.
 */
@Component
public class GitHubLifecycleNormalizer {

    private final JsonMapper json;

    /**
     * Creates a strict JSON mapper tuned for GitHub webhook payload validation.
     *
     * @param json the base mapper provided by the application container
     */
    public GitHubLifecycleNormalizer(JsonMapper json) {
        this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    /**
     * Normalizes a GitHub installation lifecycle payload into a delivery
     * object.
     * <p>
     * Unsupported event types or actions return an empty optional. Malformed
     * payloads and missing required fields raise
     * {@link InvalidGitHubPayloadException} so callers can reject invalid
     * webhook traffic without silently accepting corrupt data.
     *
     * @param eventType the GitHub event type such as installation or
     * installation_repositories
     * @param payload the raw JSON payload from GitHub
     * @return a normalized lifecycle delivery for supported events, otherwise
     * empty
     */
    public Optional<LifecycleDelivery> normalize(String eventType, byte[] payload) {
        if (!List.of("installation", "installation_repositories").contains(eventType)) {
            return Optional.empty();
        }
        JsonNode body;
        try {
            body = json.readTree(payload);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidGitHubPayloadException("Invalid GitHub lifecycle JSON");
        }
        require(body != null && body.isObject(), "payload");
        JsonNode actionNode = body.path("action");
        require(actionNode.isString() && !actionNode.asString().isBlank(), "action");
        String action = actionNode.asString();
        boolean supported = "installation".equals(eventType) ? List.of("deleted", "suspend").contains(action)
                : "removed".equals(action);
        if (!supported) {
            return Optional.empty();
        }
        long installation = positive(body.path("installation").path("id"), "installation.id");
        JsonNode account = body.path("installation").path("account");
        Long accountId = account.isMissingNode() || account.isNull() ? null
                : positive(account.path("id"), "installation.account.id");
        if ("installation".equals(eventType)) {
            require(accountId != null, "installation.account.id");
        }
        var repositories = new TreeSet<String>();
        if ("removed".equals(action)) {
            JsonNode removed = body.path("repositories_removed");
            require(removed.isArray() && removed.size() <= 1000, "repositories_removed");
            for (var repository : removed) {
                repositories.add(Long.toString(positive(repository.path("id"), "repositories_removed.id")));
            }
        }
        return Optional.of(new LifecycleDelivery(eventType, action, installation, accountId, List.copyOf(repositories)));
    }

    private static long positive(JsonNode value, String field) {
        require(value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0, field);
        return value.asLong();
    }

    private static void require(boolean valid, String field) {
        if (!valid) {
            throw new InvalidGitHubPayloadException("Missing or invalid webhook field: " + field);
        }
    }

    /**
     * Immutable representation of a normalized GitHub installation lifecycle
     * event.
     *
     * @param eventType the GitHub event type
     * @param action the lifecycle action taken by GitHub
     * @param installationId the installation identifier
     * @param accountId the installation account identifier when available
     * @param removedRepositoryIds repository identifiers removed from the
     * installation, if any
     */
    public record LifecycleDelivery(String eventType, String action, long installationId,
            Long accountId, List<String> removedRepositoryIds) {

    }
}
