package com.changeguard.connector.normalization;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.changeguard.connector.github.InvalidGitHubPayloadException;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import com.changeguard.connector.github.dto.GitHubPullRequestWebhook;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Maps verified GitHub webhook payloads to merged pull request events.
 * Signature verification must happen before invoking this component.
 */
@Component
public class GitHubEventNormalizer {

    private final ObjectReader webhookReader;

    /**
     * Creates a strict reader without changing the shared application mapper.
     *
     * @param jsonMapper the application's Jackson 3 mapper
     */
    public GitHubEventNormalizer(JsonMapper jsonMapper) {
        Assert.notNull(jsonMapper, "JSON mapper is required");
        this.webhookReader = jsonMapper.rebuild()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build()
                .readerFor(GitHubPullRequestWebhook.class);
    }

    /**
     * Normalizes a closed, merged pull request into a provider DTO for event construction.
     * Other event types and pull request actions return an empty result.
     *
     * @param eventType the verified delivery's GitHub event type
     * @param deliveryId the delivery identifier to retain in the normalized event
     * @param payload the unmodified, already verified webhook bytes
     * @return the merged pull request event, or an empty result for unsupported activity
     * @throws IllegalArgumentException if event type or delivery ID is blank
     * @throws InvalidGitHubPayloadException if a pull request payload is malformed
     * or a merged pull request lacks required correlation fields
     */
    public Optional<GitHubMergedPullRequest> normalize(String eventType, String deliveryId, byte[] payload) {
        return normalize(eventType, deliveryId, payload, false).map(GitHubMergedDelivery::pullRequest);
    }

    /**
     * Normalizes a verified delivery and requires its GitHub App installation ID.
     * The processor resolves internal organization ownership from this ID in storage.
     */
    public Optional<GitHubMergedDelivery> normalizeDelivery(String eventType, String deliveryId, byte[] payload) {
        return normalize(eventType, deliveryId, payload, true);
    }

    private Optional<GitHubMergedDelivery> normalize(String eventType, String deliveryId,
            byte[] payload, boolean requireInstallation) {
        Assert.hasText(eventType, "GitHub event type is required");
        Assert.hasText(deliveryId, "GitHub delivery ID is required");
        if (!"pull_request".equals(eventType)) {
            return Optional.empty();
        }

        require(payload != null && payload.length > 0, "payload");
        GitHubPullRequestWebhook webhook;
        try {
            webhook = webhookReader.readValue(payload);
        } catch (JacksonException exception) {
            throw new InvalidGitHubPayloadException("Invalid pull request webhook JSON", exception);
        }

        require(webhook != null, "payload");
        requireText(webhook.action(), "action");
        if (!"closed".equals(webhook.action())) {
            return Optional.empty();
        }

        var pullRequest = webhook.pullRequest();
        require(pullRequest != null, "pull_request");
        require(pullRequest.merged() != null, "pull_request.merged");
        if (!pullRequest.merged()) {
            return Optional.empty();
        }

        var repository = webhook.repository();
        require(repository != null, "repository");
        require(repository.id() != null && repository.id() > 0, "repository.id");
        requireText(repository.fullName(), "repository.full_name");
        require(pullRequest.number() != null && pullRequest.number() > 0, "pull_request.number");
        requireText(pullRequest.mergeCommitSha(), "pull_request.merge_commit_sha");
        require(pullRequest.head() != null, "pull_request.head");
        requireText(pullRequest.head().ref(), "pull_request.head.ref");
        require(pullRequest.base() != null, "pull_request.base");
        requireText(pullRequest.base().ref(), "pull_request.base.ref");
        Instant mergedAt = mergeTimestamp(pullRequest.mergedAt());

        Long installationId = webhook.installation() == null ? null : webhook.installation().id();
        if (requireInstallation) {
            require(installationId != null && installationId > 0, "installation.id");
        }
        return Optional.of(new GitHubMergedDelivery(installationId, new GitHubMergedPullRequest(
                deliveryId,
                repository.id().toString(),
                repository.fullName(),
                pullRequest.number(),
                pullRequest.title(),
                pullRequest.mergeCommitSha(),
                pullRequest.head().ref(),
                pullRequest.base().ref(),
                actorLogin(webhook),
                mergedAt)));
    }

    public record GitHubMergedDelivery(Long installationId, GitHubMergedPullRequest pullRequest) {
    }

    private static Instant mergeTimestamp(String value) {
        requireText(value, "pull_request.merged_at");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            // Keep provider-controlled field contents out of validation errors.
            throw new InvalidGitHubPayloadException("Missing or invalid webhook field: pull_request.merged_at");
        }
    }

    private static String actorLogin(GitHubPullRequestWebhook webhook) {
        var mergedBy = webhook.pullRequest().mergedBy();
        if (mergedBy != null && StringUtils.hasText(mergedBy.login())) {
            return mergedBy.login();
        }
        var sender = webhook.sender();
        return sender != null && StringUtils.hasText(sender.login()) ? sender.login() : null;
    }

    private static void requireText(String value, String field) {
        require(StringUtils.hasText(value), field);
    }

    private static void require(boolean valid, String field) {
        if (!valid) {
            throw new InvalidGitHubPayloadException("Missing or invalid webhook field: " + field);
        }
    }
}
