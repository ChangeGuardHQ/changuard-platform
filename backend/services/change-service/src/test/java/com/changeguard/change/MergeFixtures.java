package com.changeguard.change;

import java.time.Instant;
import com.changeguard.change.change.MergedChange;
import com.changeguard.change.event.EventSource;

/** Fixed provider facts keep replay and timestamp assertions deterministic. */
public final class MergeFixtures {
    public static final Instant MERGED_AT = Instant.parse("2026-10-18T12:00:00.123456Z");
    public static final String SHA = "a".repeat(40);
    private MergeFixtures() { }

    public static MergedChange merged(String event, String organization, String repository, int number) {
        return merged(event, organization, repository, number, SHA);
    }

    public static MergedChange merged(String event, String organization, String repository, int number, String sha) {
        return new MergedChange(event, organization, new EventSource("github", "integration-1"), event,
                repository, "changeguard/example", number, "Improve health checks", sha, "feature", "main", null,
                MERGED_AT, MERGED_AT.plusSeconds(5), null, null);
    }
}
