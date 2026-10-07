package com.changeguard.connector.github;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class GitHubWebhookTestSupport {
    public static final String SECRET = "test-webhook-secret";

    private GitHubWebhookTestSupport() {
    }

    public static byte[] fixture() throws Exception {
        try (var input = GitHubWebhookTestSupport.class.getResourceAsStream("/github/pull-request-merged.json")) {
            return input.readAllBytes();
        }
    }

    public static String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
