package com.changeguard.connector.api;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import com.changeguard.connector.config.SecurityConfig;
import com.changeguard.connector.github.GitHubSignatureValidator;
import com.changeguard.connector.github.GitHubWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GitHubWebhookController.class)
@Import({SecurityConfig.class, GitHubWebhookControllerTests.TestServiceConfiguration.class})
class GitHubWebhookControllerTests {

    private static final String PATH = "/api/v1/integrations/github/webhook";
    private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc0958";
    private static final String SIGNATURE = "sha256=" + "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingWebhookService webhookService;

    @BeforeEach
    void resetService() {
        webhookService.calls = 0;
        webhookService.failure = null;
        webhookService.payload = null;
    }

    @Test
    void forwardsUnmodifiedBytesAndHeadersWithoutLoginOrCsrfToken() throws Exception {
        byte[] payload = "{\r\n  \"message\": \"café 🌱\", \"number\": 1.00\r\n}\n"
                .getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=be3ef0fe859d0003770a0275c47b1ce82da028e73818c98620950a70e356f762";
        HttpHeaders headers = validHeaders();
        headers.set("X-Hub-Signature-256", signature);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(headers).content(payload))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());

        assertThat(webhookService.calls).isEqualTo(1);
        assertThat(webhookService.eventType).isEqualTo("push");
        assertThat(webhookService.deliveryId).isEqualTo(DELIVERY_ID);
        assertThat(webhookService.signature).isEqualTo(signature);
        assertThat(webhookService.payload).containsExactly(payload);
        assertThat(new GitHubSignatureValidator("It's a Secret to Everybody")
                .isValid(webhookService.payload, webhookService.signature)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ping", "pull_request", "push", "installation", "installation_repositories", "issues"})
    void delegatesEventRoutingToService(String eventType) throws Exception {
        HttpHeaders headers = validHeaders();
        headers.set("X-GitHub-Event", eventType);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(headers).content("{}"))
                .andExpect(status().isAccepted());

        assertThat(webhookService.calls).isEqualTo(1);
        assertThat(webhookService.eventType).isEqualTo(eventType);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-GitHub-Event", "X-GitHub-Delivery"})
    void rejectsMissingMetadata(String headerName) throws Exception {
        HttpHeaders headers = validHeaders();
        headers.remove(headerName);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(headers).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(webhookService.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-GitHub-Event", "X-GitHub-Delivery"})
    void rejectsBlankMetadata(String headerName) throws Exception {
        HttpHeaders headers = validHeaders();
        headers.set(headerName, " \t");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(headers).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(webhookService.calls).isZero();
    }

    @Test
    void rejectsMissingSignature() throws Exception {
        HttpHeaders headers = validHeaders();
        headers.remove("X-Hub-Signature-256");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(headers).content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(webhookService.calls).isZero();
    }

    @ParameterizedTest
    @MethodSource("malformedSignatures")
    void rejectsMalformedSignatures(String signature) throws Exception {
        HttpHeaders headers = validHeaders();
        headers.set("X-Hub-Signature-256", signature);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(headers).content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(webhookService.calls).isZero();
    }

    @Test
    void rejectsMissingBody() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(validHeaders()))
                .andExpect(status().isBadRequest());

        assertThat(webhookService.calls).isZero();
    }

    @Test
    void requiresJsonContentType() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .headers(validHeaders()).content("payload=%7B%7D"))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(webhookService.calls).isZero();
    }

    @Test
    void forwardsUnparsedPayloadToServiceForVerificationBeforeJsonParsing() throws Exception {
        byte[] payload = "{ malformed JSON".getBytes(StandardCharsets.UTF_8);
        webhookService.failure = new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook payload");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(validHeaders()).content(payload))
                .andExpect(status().isBadRequest());

        assertThat(webhookService.calls).isEqualTo(1);
        assertThat(webhookService.payload).containsExactly(payload);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 503})
    void doesNotAcknowledgeServiceRejectionsOrFailures(int statusCode) throws Exception {
        webhookService.failure = new ResponseStatusException(HttpStatus.valueOf(statusCode), "Delivery rejected");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).headers(validHeaders()).content("{}"))
                .andExpect(status().is(statusCode));

        assertThat(webhookService.calls).isEqualTo(1);
    }

    private static Stream<String> malformedSignatures() {
        return Stream.of("", " ", "sha1=" + "a".repeat(40), "sha256=", "sha256=" + "a".repeat(63),
                "sha256=" + "a".repeat(65), "sha256=" + "g".repeat(64));
    }

    private static HttpHeaders validHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "push");
        headers.set("X-GitHub-Delivery", DELIVERY_ID);
        headers.set("X-Hub-Signature-256", SIGNATURE);
        return headers;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestServiceConfiguration {

        @Bean
        RecordingWebhookService webhookService() {
            return new RecordingWebhookService();
        }
    }

    static class RecordingWebhookService implements GitHubWebhookService {

        private int calls;
        private String eventType;
        private String deliveryId;
        private String signature;
        private byte[] payload;
        private ResponseStatusException failure;

        @Override
        public void handleWebhook(String eventType, String deliveryId, String signature, byte[] payload) {
            calls++;
            this.eventType = eventType;
            this.deliveryId = deliveryId;
            this.signature = signature;
            this.payload = payload;
            if (failure != null) {
                throw failure;
            }
        }
    }
}
