package com.changeguard.change.messaging;

import com.changeguard.change.AvroFixtures;
import com.changeguard.change.change.ChangeApplicationService;
import com.changeguard.change.change.ChangeMapper;
import com.changeguard.change.exception.EventProcessingException;
import com.changeguard.events.common.ActorType;
import com.changeguard.events.common.EventActor;
import com.changeguard.events.common.EventCorrelation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Invalid records never reach the transaction; database failures escape to the stopping error handler. */
class CodeChangeEventConsumerTest {
    private final ChangeApplicationService service = mock(ChangeApplicationService.class);
    private final ChangeMapper mapper = new ChangeMapper();
    private final CodeChangeEventConsumer consumer = new CodeChangeEventConsumer(service, mapper);

    @Test
    void mapsTheSharedContractWithoutLosingNullableMetadataOrUnknownAttribution() {
        var event = AvroFixtures.merged("one", "org-one");
        event.getPayload().setPullRequestTitle(null);
        event.setActor(new EventActor(ActorType.UNKNOWN, "provider-actor"));
        event.setCorrelation(new EventCorrelation("trace-1", "upstream-reference"));
        consumer.consume(new ConsumerRecord<>("topic", 0, 0, "repo-wire", event));
        var fact = mapper.fromAvro(event);
        verify(service).ingest(fact);
        assertThat(fact.title()).isNull();
        assertThat(fact.actor().type()).isEqualTo(com.changeguard.change.event.ActorType.UNKNOWN);
        assertThat(fact.upstreamChangeId()).isEqualTo("upstream-reference");
    }

    @Test
    void semanticVersionAndPartitionIdentityAreCheckedBeforeApplyingFacts() {
        var event = AvroFixtures.merged("one", "org-one");
        event.setEventVersion(2);
        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>("topic", 0, 0, "repo-wire", event)))
                .isInstanceOf(IllegalArgumentException.class);
        event.setEventVersion(1);
        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>("topic", 0, 0, "wrong-repo", event)))
                .isInstanceOf(EventProcessingException.class);
        verifyNoInteractions(service);
    }

    @Test
    void tombstonesAndUnsupportedRecordTypesAreNotSilentlyAcknowledged() {
        for (Object value : new Object[] {null, "not-avro", new com.changeguard.events.code.CommitCreated()}) {
            assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>("topic", 0, 0, "repo-wire", value)))
                    .isInstanceOf(EventProcessingException.class);
        }
        verifyNoInteractions(service);
    }

    @Test
    void databaseFailurePropagatesToPreventAnOffsetCommit() {
        when(service.ingest(any())).thenThrow(new IllegalStateException("Database unavailable"));
        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>("topic", 0, 0, "repo-wire",
                AvroFixtures.merged("one", "org-one")))).isInstanceOf(IllegalStateException.class);
    }
}
