package com.changeguard.connector.github;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubSignatureValidatorTests {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final String REFERENCE_SIGNATURE =
            "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
    private static final String FORMATTED_PAYLOAD =
            "{\r\n  \"message\": \"café 🌱\", \"number\": 1.00\r\n}\n";
    private static final String FORMATTED_SIGNATURE =
            "sha256=be3ef0fe859d0003770a0275c47b1ce82da028e73818c98620950a70e356f762";

    private final GitHubSignatureValidator validator = new GitHubSignatureValidator(SECRET);

    @Test
    void verifiesTheReferenceVectorPublishedByGitHub() {
        // https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
        assertThat(validator.isValid(bytes("Hello, World!"), REFERENCE_SIGNATURE)).isTrue();
    }

    @Test
    void validatesUnicodeAndFormattingWithoutChangingTheRequestBytes() {
        byte[] payload = bytes(FORMATTED_PAYLOAD);
        byte[] original = payload.clone();

        assertThat(validator.isValid(payload, FORMATTED_SIGNATURE)).isTrue();
        assertThat(payload).containsExactly(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"line-endings", "number-format", "trailing-newline", "unicode-escape"})
    void rejectsChangesThatWouldBeLostByParsingAndReserializingJson(String change) {
        String altered = switch (change) {
            case "line-endings" -> FORMATTED_PAYLOAD.replace("\r\n", "\n");
            case "number-format" -> FORMATTED_PAYLOAD.replace("1.00", "1");
            case "trailing-newline" -> FORMATTED_PAYLOAD.stripTrailing();
            case "unicode-escape" -> FORMATTED_PAYLOAD.replace("é", "\\u00e9");
            default -> throw new IllegalArgumentException(change);
        };

        assertThat(validator.isValid(bytes(altered), FORMATTED_SIGNATURE)).isFalse();
    }

    @Test
    void hashesUndecodableBytesWithoutAStringRoundTrip() {
        byte[] payload = {(byte) 0xc3, 0x28, (byte) 0xff, 0x00};
        String signature = "sha256=ddae6e1beec316ea060f8c7e759abffb5ace989e33bd28f6bea4422bc56a2b27";

        assertThat(validator.isValid(payload, signature)).isTrue();
        byte[] reencoded = new String(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        assertThat(validator.isValid(reencoded, signature)).isFalse();
    }

    @Test
    void usesUtf8ForTheConfiguredSecret() {
        var unicodeSecretValidator = new GitHubSignatureValidator("sëcret 🌱");

        assertThat(unicodeSecretValidator.isValid(bytes("{\"message\":\"café 🌱\"}"),
                "sha256=9ccf0db6675fe034fddb9aebfe32ea395061ad8923de511ad42889e95d62e1e0")).isTrue();
    }

    @Test
    void acceptsUppercaseHexDigitsAsTheControllerDoes() {
        String uppercaseHex = "sha256=" + REFERENCE_SIGNATURE.substring(7).toUpperCase(Locale.ROOT);

        assertThat(validator.isValid(bytes("Hello, World!"), uppercaseHex)).isTrue();
    }

    @Test
    void rejectsAWrongSecretOrDigest() {
        assertThat(new GitHubSignatureValidator("different-secret")
                .isValid(bytes("Hello, World!"), REFERENCE_SIGNATURE)).isFalse();
        assertThat(validator.isValid(bytes("Hello, World!"), "sha256=" + "0".repeat(64))).isFalse();
    }

    @Test
    void rejectsMissingPayload() {
        assertThat(validator.isValid(null, REFERENCE_SIGNATURE)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("malformedSignatures")
    void rejectsMalformedSignaturesWithoutThrowing(String signature) {
        assertThat(validator.isValid(bytes("Hello, World!"), signature)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingSecretConfiguration(String secret) {
        assertThatThrownBy(() -> new GitHubSignatureValidator(secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("GitHub webhook secret must not be blank");
    }

    private static Stream<String> malformedSignatures() {
        return Stream.of(null, "", " ", "sha1=" + "a".repeat(40), "SHA256=" + "a".repeat(64),
                "sha256=", "sha256=" + "a".repeat(63), "sha256=" + "a".repeat(65),
                "sha256=" + "g".repeat(64), REFERENCE_SIGNATURE + "\n", " " + REFERENCE_SIGNATURE);
    }

    private static byte[] bytes(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }
}
