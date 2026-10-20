package com.changeguard.connector.normalization;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import com.changeguard.connector.event.CodeLifecycleEvent;
import com.changeguard.connector.github.InvalidGitHubPayloadException;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Normalizes verified opened-PR and branch-push deliveries without trusting provider organization fields. */
@Component
public class GitHubCodeLifecycleNormalizer {
    private final JsonMapper json;
    public GitHubCodeLifecycleNormalizer(JsonMapper json) {
        this.json = json.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }

    public Optional<Delivery> normalize(String eventType, String deliveryId, byte[] bytes) {
        if (!"pull_request".equals(eventType) && !"push".equals(eventType)) return Optional.empty();
        if (bytes == null || bytes.length == 0) throw invalid("payload");
        try {
            var root = json.readTree(bytes);
            if (root == null || !root.isObject()) throw invalid("payload");
            boolean opened = "pull_request".equals(eventType);
            if (opened && !"opened".equals(text(root, "action"))) return Optional.empty();
            if (!opened) {
                var deleted = root.path("deleted");
                if (!deleted.isMissingNode() && !deleted.isBoolean()) throw invalid("deleted");
                if (deleted.asBoolean(false) || !text(root, "ref").startsWith("refs/heads/")) return Optional.empty();
            }
            long installation = positive(root.path("installation"), "id");
            var repository = root.path("repository");
            long owner = positive(repository.path("owner"), "id");
            String repositoryId = Long.toString(positive(repository, "id"));
            String repositoryName = text(repository, "full_name");
            String actor = optionalText(root.path("sender"), "login");
            var facts = new ArrayList<Fact>();
            if (opened) {
                var pr = root.path("pull_request");
                long number = positive(pr, "number");
                if (number > Integer.MAX_VALUE) throw invalid("pull_request.number");
                facts.add(new Fact("PullRequestOpened", instant(pr, "created_at"), actor,
                        new CodeLifecycleEvent.Payload(deliveryId, repositoryId, repositoryName, (int) number,
                                optionalText(pr, "title"), sha(pr.path("head"), "sha"), text(pr.path("head"), "ref"),
                                text(pr.path("base"), "ref"), null, null, null)));
            } else {
                var commits = root.path("commits");
                if (!commits.isArray() || commits.size() > 2048) throw invalid("commits");
                String branch = text(root, "ref").substring("refs/heads/".length());
                if (branch.isBlank()) throw invalid("ref");
                var distinct = new LinkedHashMap<String, Fact>();
                for (var commit : commits) {
                    String sha = sha(commit, "id");
                    var fact = new Fact("CommitCreated", instant(commit, "timestamp"), actor,
                            new CodeLifecycleEvent.Payload(deliveryId, repositoryId, repositoryName, null,
                                    null, null, null, null, sha, optionalText(commit, "message"), branch));
                    var previous = distinct.putIfAbsent(sha, fact);
                    if (previous != null && !previous.equals(fact)) throw invalid("commits");
                }
                facts.addAll(distinct.values());
            }
            return facts.isEmpty() ? Optional.empty() : Optional.of(new Delivery(installation, owner, List.copyOf(facts)));
        } catch (InvalidGitHubPayloadException invalid) { throw invalid; }
        catch (tools.jackson.core.JacksonException invalid) { throw new InvalidGitHubPayloadException("Invalid lifecycle webhook payload"); }
    }

    public record Fact(String type, Instant occurredAt, String actor, CodeLifecycleEvent.Payload payload) { }
    public record Delivery(long installationId, long repositoryOwnerId, List<Fact> facts) {
        public List<CodeLifecycleEvent> events(String organization, String integration, Instant received) {
            return facts.stream().map(fact -> CodeLifecycleEvent.fromGitHub(fact.type(), fact.occurredAt(), received,
                    organization, integration, fact.actor(), fact.payload())).toList();
        }
    }

    private static String text(JsonNode node, String field) {
        String text = optionalText(node, field);
        if (text == null || text.isBlank()) throw invalid(field);
        return text;
    }
    private static String optionalText(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) throw invalid(field);
        return value.asString();
    }
    private static long positive(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) throw invalid(field);
        return value.asLong();
    }
    private static String sha(JsonNode node, String field) {
        String sha = text(node, field);
        if (!sha.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})")) throw invalid(field);
        return sha.toLowerCase(Locale.ROOT);
    }
    private static Instant instant(JsonNode node, String field) {
        try { return Instant.parse(text(node, field)); }
        catch (RuntimeException invalid) { throw invalid(field); }
    }
    private static InvalidGitHubPayloadException invalid(String field) {
        return new InvalidGitHubPayloadException("Missing or invalid webhook field: " + field);
    }
}
