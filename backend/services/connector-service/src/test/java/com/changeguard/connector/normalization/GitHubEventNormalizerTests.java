package com.changeguard.connector.normalization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Stream;

import com.changeguard.connector.github.InvalidGitHubPayloadException;
import com.changeguard.connector.github.dto.GitHubMergedPullRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubEventNormalizerTests {

    private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
    private static final String MERGE_SHA = "cccccccccccccccccccccccccccccccccccccccc";

    private JsonMapper jsonMapper;
    private GitHubEventNormalizer normalizer;
    private byte[] fixture;
    private ObjectNode payload;

    @BeforeEach
    void setUp() throws IOException {
        jsonMapper = JsonMapper.builder().build();
        normalizer = new GitHubEventNormalizer(jsonMapper);
        try (var input = getClass().getResourceAsStream("/github/pull-request-merged.json")) {
            assertThat(input).isNotNull();
            fixture = input.readAllBytes();
        }
        payload = (ObjectNode) jsonMapper.readTree(fixture);
    }

    @Test
    void mapsMergedPullRequestAndRetainsTheMergeCommitAndMergeActor() {
        assertThat(normalizer.normalize("pull_request", DELIVERY_ID, fixture))
                .contains(new GitHubMergedPullRequest(
                        DELIVERY_ID, "12345", "changeguard/example", 42, "Fix café checkout 🌱",
                        MERGE_SHA, "feature/checkout", "main", "release-manager",
                        Instant.parse("2026-10-04T12:00:00Z")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ping", "push", "installation", "pull_request_review", "future_event"})
    void ignoresUnsupportedEventTypesWithoutParsingTheirPayloads(String eventType) {
        assertThat(normalizer.normalize(eventType, DELIVERY_ID, bytes("not pull request JSON"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"opened", "synchronize", "reopened", "edited", "assigned", "future_action"})
    void doesNotInferAMergeFromOtherActions(String action) {
        payload.put("action", action);
        assertThat(normalizer.normalize("pull_request", DELIVERY_ID, payloadBytes())).isEmpty();
    }

    @Test
    void ignoresClosedUnmergedPullRequestsEvenWithoutMergeMetadata() {
        pullRequest().put("merged", false);
        pullRequest().putNull("merge_commit_sha");
        pullRequest().putNull("merged_at");
        payload.remove("repository");

        assertThat(normalizer.normalize("pull_request", DELIVERY_ID, payloadBytes())).isEmpty();
    }

    @Test
    void usesSenderWhenMergeActorIsAbsent() {
        pullRequest().remove("merged_by");

        assertThat(normalize().actorLogin()).isEqualTo("automation-bot");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void usesSenderWhenMergeActorLoginIsMissingOrBlank(String login) {
        ((ObjectNode) pullRequest().get("merged_by")).put("login", login);

        assertThat(normalize().actorLogin()).isEqualTo("automation-bot");
    }

    @Test
    void keepsMissingOptionalFieldsNullAndDoesNotUseThePullRequestAuthorAsMergeActor() {
        pullRequest().remove("title");
        pullRequest().remove("merged_by");
        payload.remove("sender");

        GitHubMergedPullRequest result = normalize();
        assertThat(result.pullRequestTitle()).isNull();
        assertThat(result.actorLogin()).isNull();
    }

    @Test
    void retainsLargeRepositoryIdentifiersWithoutPrecisionLoss() {
        ((ObjectNode) payload.get("repository")).put("id", 9007199254740993L);

        assertThat(normalize().repositoryId()).isEqualTo("9007199254740993");
    }

    @Test
    void repeatedNormalizationRetainsTheSameDeliveryIdentityAndMapping() {
        var first = normalizer.normalize("pull_request", DELIVERY_ID, fixture);
        var repeated = normalizer.normalize("pull_request", DELIVERY_ID, fixture);

        assertThat(repeated).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2026-10-04", "2026-10-04T12:00:00", "2026-13-04T12:00:00Z"})
    void rejectsInvalidMergeTimestampsWithoutIncludingTheirValuesInTheError(String timestamp) {
        pullRequest().put("merged_at", timestamp);

        assertThatThrownBy(this::normalize)
                .isInstanceOf(InvalidGitHubPayloadException.class)
                .hasMessage("Missing or invalid webhook field: pull_request.merged_at")
                .hasNoCause();
    }

    @Test
    void normalizesAnOffsetMergeTimestampToTheSameInstant() {
        pullRequest().put("merged_at", "2026-10-04T07:00:00-05:00");

        assertThat(normalize().mergedAt()).isEqualTo(Instant.parse("2026-10-04T12:00:00Z"));
    }

    @ParameterizedTest
    @MethodSource("requiredFields")
    void rejectsMissingRequiredCorrelationFields(String path) {
        parentOf(path).remove(fieldName(path));

        assertThatThrownBy(() -> normalize())
                .isInstanceOf(InvalidGitHubPayloadException.class)
                .hasMessageContaining(path.substring(1).replace('/', '.'));
    }

    @ParameterizedTest
    @MethodSource("requiredFields")
    void rejectsNullRequiredCorrelationFields(String path) {
        parentOf(path).putNull(fieldName(path));

        assertThatThrownBy(() -> normalize()).isInstanceOf(InvalidGitHubPayloadException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/action", "/repository/full_name", "/pull_request/merge_commit_sha",
            "/pull_request/head/ref", "/pull_request/base/ref", "/pull_request/merged_at"})
    void rejectsBlankRequiredText(String path) {
        parentOf(path).put(fieldName(path), " \t");

        assertThatThrownBy(() -> normalize()).isInstanceOf(InvalidGitHubPayloadException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidFieldValues")
    void rejectsIncorrectTypesAndInvalidNumericIdentities(String path, String jsonValue) {
        parentOf(path).set(fieldName(path), jsonMapper.readTree(jsonValue));

        assertThatThrownBy(() -> normalize()).isInstanceOf(InvalidGitHubPayloadException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "null", "[]", "\"text\"", "42", "{", "{}"})
    void rejectsMissingOrMalformedPullRequestPayloads(String input) {
        assertThatThrownBy(() -> normalizer.normalize("pull_request", DELIVERY_ID,
                input == null ? null : bytes(input)))
                .isInstanceOf(InvalidGitHubPayloadException.class);
    }

    @Test
    void rejectsTrailingJsonAfterAnOtherwiseValidMergedPullRequest() {
        String input = new String(fixture, StandardCharsets.UTF_8) + " {}";

        assertThatThrownBy(() -> normalizer.normalize("pull_request", DELIVERY_ID, bytes(input)))
                .isInstanceOf(InvalidGitHubPayloadException.class)
                .hasMessage("Invalid pull request webhook JSON");
    }

    @Test
    void rejectsContradictoryDuplicateMergeFlags() {
        String input = new String(fixture, StandardCharsets.UTF_8)
                .replace("\"merged\": true", "\"merged\": false, \"merged\": true");

        assertThatThrownBy(() -> normalizer.normalize("pull_request", DELIVERY_ID, bytes(input)))
                .isInstanceOf(InvalidGitHubPayloadException.class)
                .hasMessage("Invalid pull request webhook JSON");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void requiresDeliveryIdentity(String deliveryId) {
        assertThatThrownBy(() -> normalizer.normalize("pull_request", deliveryId, fixture))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotModifyInputBytesOrSharedMapperConfiguration() {
        byte[] original = fixture.clone();
        var originalConfig = jsonMapper.deserializationConfig();
        normalizer.normalize("pull_request", DELIVERY_ID, fixture);

        assertThat(fixture).containsExactly(original);
        assertThat(jsonMapper.deserializationConfig()).isSameAs(originalConfig);
    }

    private GitHubMergedPullRequest normalize() {
        return normalizer.normalize("pull_request", DELIVERY_ID, payloadBytes()).orElseThrow();
    }

    private byte[] payloadBytes() {
        return jsonMapper.writeValueAsBytes(payload);
    }

    private ObjectNode pullRequest() {
        return (ObjectNode) payload.get("pull_request");
    }

    private ObjectNode parentOf(String path) {
        return (ObjectNode) payload.at(path.substring(0, path.lastIndexOf('/')));
    }

    private static String fieldName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Stream<String> requiredFields() {
        return Stream.of("/action", "/pull_request", "/pull_request/merged", "/repository",
                "/repository/id", "/repository/full_name", "/pull_request/number",
                "/pull_request/merge_commit_sha", "/pull_request/head", "/pull_request/head/ref",
                "/pull_request/base", "/pull_request/base/ref", "/pull_request/merged_at");
    }

    private static Stream<Arguments> invalidFieldValues() {
        return Stream.of(
                Arguments.of("/repository/id", "0"),
                Arguments.of("/repository/id", "-1"),
                Arguments.of("/repository/id", "1.5"),
                Arguments.of("/repository/id", "\"12345\""),
                Arguments.of("/repository/id", "9223372036854775808"),
                Arguments.of("/pull_request/number", "0"),
                Arguments.of("/pull_request/number", "-1"),
                Arguments.of("/pull_request/number", "42.5"),
                Arguments.of("/pull_request/number", "\"42\""),
                Arguments.of("/pull_request/number", "2147483648"),
                Arguments.of("/pull_request/merged", "\"true\""),
                Arguments.of("/pull_request/merged", "1"),
                Arguments.of("/repository/full_name", "12345"),
                Arguments.of("/pull_request/merge_commit_sha", "true"),
                Arguments.of("/pull_request/merged_at", "1780000000"));
    }
}
