package com.changeguard.connector.github.connection;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only fixed GitHub API paths are requested; response-provided URLs and redirects are never followed. */
public class GitHubAppClient implements GitHubProvider {
    private final GitHubConnectionProperties properties;
    private final GitHubAppAuthentication authentication;
    private final RestClient client;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public GitHubAppClient(GitHubConnectionProperties properties, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        authentication = properties.enabled()
                ? new GitHubAppAuthentication(properties.privateKeyPath(), properties.appId(), clock) : null;
        client = builder.baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "ChangeGuard-Connector")
                .defaultHeader("X-GitHub-Api-Version", "2026-03-10")
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), (request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        throw forbidden();
                    }
                    throw unavailable();
                }).build();
    }

    @Override
    public Installation installation(long installationId) {
        JsonNode body = get("/app/installations/" + installationId, appToken());
        var account = body.path("account");
        long id = positive(body.path("id"));
        long appId = positive(body.path("app_id"));
        if (id != installationId || appId != properties.appId()) {
            throw forbidden();
        }
        String type = text(account.path("type"));
        if (!List.of("Organization", "User").contains(type) || body.path("suspended_at").isMissingNode()) {
            throw unavailable();
        }
        return new Installation(id, appId, positive(account.path("id")), text(account.path("login")),
                type, !body.path("suspended_at").isNull());
    }

    @Override
    public List<GitHubRepository> repositories(long installationId) {
        JsonNode credential;
        try {
            String body = client.post().uri("/app/installations/{id}/access_tokens", installationId)
                    .headers(headers -> headers.setBearerAuth(appToken()))
                    .body(Map.of("permissions", Map.of("metadata", "read")))
                    .retrieve().body(String.class);
            credential = parse(body);
        } catch (RestClientException exception) {
            throw unavailable();
        }
        String token = text(credential.path("token"));
        try {
            if (!Instant.parse(text(credential.path("expires_at"))).isAfter(clock.instant().plusSeconds(30))) {
                throw unavailable();
            }
        } catch (java.time.format.DateTimeParseException exception) {
            throw unavailable();
        }
        var repositories = new ArrayList<GitHubRepository>();
        var ids = new HashSet<Long>();
        for (int page = 1; page <= 100; page++) {
            JsonNode entries = get("/installation/repositories?per_page=100&page=" + page, token)
                    .path("repositories");
            if (!entries.isArray() || entries.size() > 100) {
                throw unavailable();
            }
            for (var entry : entries) {
                long id = positive(entry.path("id"));
                if (!ids.add(id)) {
                    throw unavailable();
                }
                JsonNode branch = entry.path("default_branch");
                repositories.add(new GitHubRepository(id, positive(entry.path("owner").path("id")),
                        text(entry.path("full_name")), branch.isNull() || branch.isMissingNode() ? null : text(branch)));
            }
            if (entries.size() < 100) {
                return List.copyOf(repositories);
            }
        }
        throw unavailable(); // Fail rather than silently offer an incomplete installation catalog.
    }

    private String appToken() {
        if (authentication == null) {
            throw unavailable();
        }
        return authentication.token();
    }

    private JsonNode get(String path, String token) {
        try {
            return parse(client.get().uri(path).headers(headers -> headers.setBearerAuth(token))
                    .retrieve().body(String.class));
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private JsonNode parse(String body) {
        try {
            JsonNode parsed = json.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw unavailable();
            }
            return parsed;
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    private static long positive(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.asLong() <= 0) {
            throw unavailable();
        }
        return node.asLong();
    }

    private static String text(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) {
            throw unavailable();
        }
        return node.asString();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub API is unavailable");
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "GitHub installation is not accessible to this app");
    }
}
