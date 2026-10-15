package com.changeguard.connector.github.connection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Opaque state authenticated with a separate server secret. PostgreSQL enforces expiration and single use. */
@Component
public class GitHubInstallationState {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public GitHubInstallationState(GitHubConnectionProperties properties) {
        key = properties.enabled()
                ? new SecretKeySpec(properties.stateSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256") : null;
    }

    public String create() {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encoded));
    }

    public String verifiedHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        byte[] signature = Base64.getUrlDecoder().decode(token.substring(44));
        if (!MessageDigest.isEqual(sign(token.substring(0, 43)), signature)) {
            throw invalid();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Callback state hashing is unavailable");
        }
    }

    private byte[] sign(String nonce) {
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub connection is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(nonce.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Callback state signing is unavailable");
        }
    }

    static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid, expired, or already used installation state");
    }
}
