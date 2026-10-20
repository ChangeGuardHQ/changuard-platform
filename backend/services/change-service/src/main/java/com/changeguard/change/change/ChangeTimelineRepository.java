package com.changeguard.change.change;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.changeguard.change.event.Actor;
import com.changeguard.change.event.ActorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only application access; writes join the projection/receipt transaction. */
@Repository
public class ChangeTimelineRepository {
    private final JdbcClient jdbc;
    public ChangeTimelineRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void appendMerge(UUID changeId, MergedChange merge) {
        append(merge.organizationId(), changeId, "merge", merge.source().integrationId(),
                new TimelineFact(merge.eventId(), "PullRequestMerged", merge.mergedAt(), merge.commitSha(), merge.actor()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String organization, UUID changeId, String factKey, String integration, TimelineFact fact) {
        jdbc.sql("""
                INSERT INTO change.change_timeline (organization_id, change_id, event_type, fact_key, event_id,
                        occurred_at, commit_sha, actor_type, actor_id, integration_id)
                VALUES (:organization, :change, :type, :factKey, :event,
                        :occurred, :commit, :actorType, :actorId, :integration)
                ON CONFLICT (organization_id, change_id, event_type, fact_key) DO NOTHING
                """)
                .param("organization", organization).param("change", changeId).param("type", fact.type())
                .param("factKey", factKey).param("event", fact.eventId())
                .param("occurred", fact.occurredAt().atOffset(ZoneOffset.UTC)).param("commit", fact.commitSha())
                .param("actorType", fact.actor() == null ? null : fact.actor().type().name())
                .param("actorId", fact.actor() == null ? null : fact.actor().id()).param("integration", integration).update();
    }

    public List<TimelineFact> find(String organization, UUID changeId) {
        // Scope both the change and the timeline; ordering uses occurrence time, not delivery time.
        return jdbc.sql("""
                SELECT event_id, event_type, occurred_at, commit_sha, actor_type, actor_id
                FROM change.change_timeline WHERE organization_id = :organization AND change_id = :change
                ORDER BY occurred_at, event_type, event_id
                """).param("organization", organization).param("change", changeId)
                .query((rs, row) -> new TimelineFact(rs.getString("event_id"), rs.getString("event_type"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("commit_sha"),
                        rs.getString("actor_type") == null ? null : new Actor(
                                ActorType.valueOf(rs.getString("actor_type")), rs.getString("actor_id"))))
                .list();
    }
}
