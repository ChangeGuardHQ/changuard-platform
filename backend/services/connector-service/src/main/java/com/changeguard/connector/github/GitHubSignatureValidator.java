package com.changeguard.connector.github;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class GitHubSignatureValidator {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final SecretKeySpec secretKey;

    /**
     * Creates a validator for GitHub webhook payload signatures.
     *
     * @param webhookSecret the shared secret configured for GitHub webhook
     * verification
     */
    public GitHubSignatureValidator(
            @Value("${changeguard.github.webhook-secret}") String webhookSecret
    ) {
        Assert.hasText(webhookSecret, "GitHub webhook secret must not be blank");
        this.secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
    }

    /**
     * Verifies whether the supplied GitHub webhook payload matches the expected
     * HMAC-SHA256 signature contained in the X-Hub-Signature-256 header.
     *
     * Hashes the unmodified request bytes before any decoding or JSON parsing.
     *
     * @param payload the raw request body received from GitHub
     * @param signatureHeader the signature header value, including the required
     * {@code sha256=} prefix
     * @return {@code true} if the payload matches the expected signature,
     * otherwise {@code false}
     */
    public boolean isValid(byte[] payload, String signatureHeader) {

        if (payload == null || signatureHeader == null) {
            return false;
        }

        if (!signatureHeader.startsWith(SIGNATURE_PREFIX)
                || signatureHeader.length() != SIGNATURE_PREFIX.length() + 64) {
            return false;
        }

        byte[] expectedHash;
        try {
            expectedHash = HexFormat.of().parseHex(signatureHeader.substring(SIGNATURE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(secretKey);
            return MessageDigest.isEqual(mac.doFinal(payload), expectedHash);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to validate GitHub webhook signature",
                    exception
            );
        }
    }
}
