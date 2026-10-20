package com.changeguard.connector;

import java.nio.charset.StandardCharsets;

/** Credential-free GitHub App deliveries; a push deliberately carries two independent commit identities. */
public final class LifecycleWebhookFixtures {
    private LifecycleWebhookFixtures() { }
    public static byte[] opened() {
        return """
                {"action":"opened","installation":{"id":123},"repository":{"id":12345,
                 "full_name":"changeguard/example","owner":{"id":99}},"sender":{"login":"contributor"},
                 "pull_request":{"number":42,"title":"Initial title","created_at":"2026-10-04T10:00:00Z",
                  "head":{"ref":"feature/checkout","sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                  "base":{"ref":"main"}}}
                """.getBytes(StandardCharsets.UTF_8);
    }
    public static byte[] push() {
        return """
                {"ref":"refs/heads/main","deleted":false,"installation":{"id":123},
                 "repository":{"id":12345,"full_name":"changeguard/example","owner":{"id":99}},
                 "sender":{"login":"pusher"},"commits":[
                  {"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","message":"First commit","timestamp":"2026-10-04T09:00:00Z"},
                  {"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","message":"Second commit","timestamp":"2026-10-04T09:30:00Z"}]}
                """.getBytes(StandardCharsets.UTF_8);
    }
}
