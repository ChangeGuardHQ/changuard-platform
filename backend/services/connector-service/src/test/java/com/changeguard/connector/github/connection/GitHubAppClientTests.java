package com.changeguard.connector.github.connection;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class GitHubAppClientTests {
    private static final Instant NOW = Instant.parse("2026-10-09T18:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SECRET = "state-test-secret-with-more-than-32-bytes";
    @TempDir Path temporary;
    private Path keyFile;
    private KeyPair pair;
    private MockRestServiceServer server;
    private GitHubAppClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void prepare() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        keyFile = temporary.resolve("app-key.pem");
        writePem("PRIVATE KEY", pair.getPrivate().getEncoded());
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubAppClient(properties(), builder, CLOCK);
    }

    @Test
    void appJwtUsesExpectedIssuerLifetimeAndValidRsaSignature() throws Exception {
        String token = new GitHubAppAuthentication(keyFile.toString(), 7, CLOCK).token();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        var claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("iss").asString()).isEqualTo("7");
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond() - 60);
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 540);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void githubPkcs1PemAndPkcs8PemProduceTheSameJwt() throws Exception {
        String pkcs8 = new GitHubAppAuthentication(keyFile.toString(), 7, CLOCK).token();
        var key = (RSAPrivateCrtKey) pair.getPrivate();
        var values = List.of(BigInteger.ZERO, key.getModulus(), key.getPublicExponent(), key.getPrivateExponent(),
                key.getPrimeP(), key.getPrimeQ(), key.getPrimeExponentP(), key.getPrimeExponentQ(), key.getCrtCoefficient());
        var bytes = new java.io.ByteArrayOutputStream();
        for (var value : values) {
            bytes.writeBytes(der(0x02, value.toByteArray()));
        }
        writePem("RSA PRIVATE KEY", der(0x30, bytes.toByteArray()));
        assertThat(new GitHubAppAuthentication(keyFile.toString(), 7, CLOCK).token()).isEqualTo(pkcs8);
    }

    @Test
    void malformedKeyIsRejectedWithoutExposingItsContents() throws Exception {
        Files.writeString(keyFile, "sensitive-invalid-private-key");
        assertThatThrownBy(() -> new GitHubAppAuthentication(keyFile.toString(), 7, CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to load a valid GitHub App RSA private key").hasNoCause();
    }

    @Test
    void installationMetadataIsVerifiedUsingThisAppsJwtAndPinnedApiVersion() {
        server.expect(requestTo("https://api.github.com/app/installations/123"))
                .andExpect(header("Authorization", startsWith("Bearer ")))
                .andExpect(header("X-GitHub-Api-Version", "2026-03-10"))
                .andRespond(withSuccess(installation(7, "99"), MediaType.APPLICATION_JSON));
        assertThat(client.installation(123)).isEqualTo(new GitHubProvider.Installation(123, 7, 99,
                "changeguard", "Organization", false));
        server.verify();
    }

    @Test
    void repositoryCatalogUsesMetadataOnlyTokenAndFixedPaginationPaths() {
        expectToken();
        server.expect(requestTo("https://api.github.com/installation/repositories?per_page=100&page=1"))
                .andExpect(header("Authorization", "Bearer test-installation-token"))
                .andRespond(withSuccess(repositories(100, 1), MediaType.APPLICATION_JSON)
                        .header("Link", "<https://untrusted.example/steal>; rel=\"next\""));
        server.expect(requestTo("https://api.github.com/installation/repositories?per_page=100&page=2"))
                .andExpect(header("Authorization", "Bearer test-installation-token"))
                .andRespond(withSuccess(repositories(1, 101), MediaType.APPLICATION_JSON));
        var catalog = client.repositories(123);
        assertThat(catalog).hasSize(101);
        assertThat(catalog.getLast().repositoryId()).isEqualTo(101);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"99\"", "99.5", "0", "-1", "9223372036854775808"})
    void providerAccountIdsCannotBeCoercedOrTruncated(String account) {
        server.expect(requestTo("https://api.github.com/app/installations/123"))
                .andRespond(withSuccess(installation(7, account), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.installation(123)).isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void installationFromAnotherAppIsRejected() {
        server.expect(requestTo("https://api.github.com/app/installations/123"))
                .andRespond(withSuccess(installation(8, "99"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.installation(123)).isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 401, 403, 429, 500})
    void providerErrorsAndRedirectsDoNotExposeResponseBodies(int status) {
        server.expect(requestTo("https://api.github.com/app/installations/123"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("sensitive-provider-error")
                        .header("Location", "https://untrusted.example/steal"));
        assertThatThrownBy(() -> client.installation(123)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GitHub API is unavailable").hasMessageNotContaining("sensitive-provider-error").hasNoCause();
    }

    @Test
    void duplicateRepositoriesAcrossPagesRejectIncompleteCatalog() {
        expectToken();
        server.expect(requestTo("https://api.github.com/installation/repositories?per_page=100&page=1"))
                .andRespond(withSuccess(repositories(100, 1), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/installation/repositories?per_page=100&page=2"))
                .andRespond(withSuccess(repositories(1, 1), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.repositories(123)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GitHub API is unavailable");
    }

    @Test
    void stateIsOpaqueSignedAndCannotBeAlteredOrValidatedWithAnotherSecret() {
        var states = new GitHubInstallationState(properties());
        String state = states.create();
        assertThat(state).hasSize(87).doesNotContain("internal-org");
        assertThat(states.verifiedHash(state)).hasSize(64);
        var wrongProperties = new GitHubConnectionProperties(true, 7, "changeguard-test", keyFile.toString(),
                "different-state-secret-with-at-least-32-bytes", Duration.ofMinutes(10), properties().organizations());
        assertThatThrownBy(() -> new GitHubInstallationState(wrongProperties).verifiedHash(state))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Invalid, expired");
    }

    @Test
    void invalidAndDuplicateAuthorizationBindingsFailConfigurationWithoutLeakingSecrets() {
        assertThat(properties().toString()).doesNotContain(SECRET);
        assertThatThrownBy(() -> new GitHubConnectionProperties(true, 7, "changeguard-test", keyFile.toString(),
                SECRET, Duration.ofMinutes(10), List.of(
                        new GitHubConnectionProperties.OrganizationBinding("org-a", 99, Set.of("alice")),
                        new GitHubConnectionProperties.OrganizationBinding("org-b", 99, Set.of("bob")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
        assertThatThrownBy(() -> new GitHubConnectionProperties(true, 7, "changeguard-test", keyFile.toString(),
                "short", Duration.ofMinutes(10), properties().organizations()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32 bytes");
    }

    private GitHubConnectionProperties properties() {
        return new GitHubConnectionProperties(true, 7, "changeguard-test", keyFile.toString(), SECRET,
                Duration.ofMinutes(10), List.of(new GitHubConnectionProperties.OrganizationBinding("internal-org", 99, Set.of("alice"))));
    }

    private void expectToken() {
        server.expect(requestTo("https://api.github.com/app/installations/123/access_tokens"))
                .andExpect(method(POST)).andExpect(header("Authorization", startsWith("Bearer ")))
                .andExpect(content().json("{\"permissions\":{\"metadata\":\"read\"}}"))
                .andRespond(withSuccess("{\"token\":\"test-installation-token\",\"expires_at\":\"2026-10-09T19:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
    }

    private String installation(long app, String account) {
        return "{\"id\":123,\"app_id\":" + app + ",\"account\":{\"id\":" + account
                + ",\"login\":\"changeguard\",\"type\":\"Organization\"},\"suspended_at\":null}";
    }

    private String repositories(int count, int start) {
        return "{\"repositories\":[" + IntStream.range(start, start + count)
                .mapToObj(id -> "{\"id\":" + id + ",\"owner\":{\"id\":99},\"full_name\":\"changeguard/repo-"
                        + id + "\",\"default_branch\":\"main\"}").collect(Collectors.joining(",")) + "]}";
    }

    private void writePem(String label, byte[] bytes) throws Exception {
        Files.writeString(keyFile, "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----\n");
    }

    private static byte[] der(int tag, byte[] content) {
        var output = new java.io.ByteArrayOutputStream();
        output.write(tag);
        if (content.length < 128) {
            output.write(content.length);
        } else if (content.length < 256) {
            output.write(0x81);
            output.write(content.length);
        } else {
            output.write(0x82);
            output.write(content.length >> 8);
            output.write(content.length);
        }
        output.writeBytes(content);
        return output.toByteArray();
    }
}
