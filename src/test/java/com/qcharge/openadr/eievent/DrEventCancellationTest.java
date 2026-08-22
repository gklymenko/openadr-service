package com.qcharge.openadr.eievent;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.TestSessionFixtures;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.ei.EventStatusEnumeratedType;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.resource.EventResourceResolver;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedEventTarget;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.qcharge.openadr.support.EventProtocolTestComponents.protocolAdapter;

@ExtendWith(MockitoExtension.class)
class DrEventCancellationTest extends AbstractOadrTest {

    @Mock
    private DrEventRepository repository;
    @Mock
    private VtnTransportService transportService;
    @Mock
    private EventResourceResolver eventResourceResolver;
    private EventProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setResourceId("RES_123");
        org.mockito.Mockito.lenient().when(eventResourceResolver.resolveEventTarget(any(), any()))
                .thenReturn(new ResolvedEventTarget(List.of()));
        adapter = protocolAdapter(
                repository,
                transportService,
                new EventValidationService(properties),
                eventResourceResolver
        );
    }

    @Test
    void emptySnapshotImplicitlyCancelsPendingEventWithoutAcknowledgement() {
        DrEvent event = knownEvent(DrEvent.ExecutionStatus.SCHEDULED);
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        adapter.receive(emptySnapshot(), session());

        ArgumentCaptor<DrEvent> captor = ArgumentCaptor.forClass(DrEvent.class);
        verify(repository).save(captor.capture());
        DrEvent cancelled = captor.getValue();
        assertEquals(DrEvent.CancellationType.IMPLICIT, cancelled.getCancellationType());
        assertEquals(DrEvent.EventStatus.CANCELLED, cancelled.getStatus());
        assertEquals(DrEvent.ExecutionStatus.CANCELLED, cancelled.getExecutionStatus());
        assertEquals(cancelled.getCancellationRequestedAt(), cancelled.getCancellationEffectiveAt());
        verify(transportService, never()).send(any(), any(), any());
    }

    @Test
    void implicitCancellationOfAppliedEventSchedulesRandomizedTermination() {
        DrEvent event = knownEvent(DrEvent.ExecutionStatus.APPLIED);
        event.setStatus(DrEvent.EventStatus.ACTIVE);
        event.setStartAfterSeconds(120L);
        Instant before = Instant.now();
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        adapter.receive(emptySnapshot(), session());

        assertEquals(DrEvent.CancellationType.IMPLICIT, event.getCancellationType());
        assertEquals(DrEvent.ExecutionStatus.CANCEL_PENDING, event.getExecutionStatus());
        assertEquals(DrEvent.EventStatus.ACTIVE, event.getStatus());
        assertNotNull(event.getCancellationEffectiveAt());
        assertFalse(event.getCancellationRequestedAt().isBefore(before));
        long terminationOffset = Duration.between(
                event.getCancellationRequestedAt(),
                event.getCancellationEffectiveAt()
        ).getSeconds();
        assertTrue(terminationOffset >= 0L && terminationOffset <= 120L);
    }

    @Test
    void explicitCancellationOfAppliedEventIsAcknowledgedBeforeTermination()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType snapshot = jaxbContext.unmarshal(
                new File(EIEVENT_PATH + "oadrDistributeEvent.xml"),
                OadrDistributeEventType.class
        );
        var descriptor = snapshot.getOadrEvent().getFirst().getEiEvent().getEventDescriptor();
        descriptor.setModificationNumber(1L);
        descriptor.setEventStatus(EventStatusEnumeratedType.CANCELLED);

        DrEvent event = knownEvent(DrEvent.ExecutionStatus.APPLIED);
        event.setStatus(DrEvent.EventStatus.ACTIVE);
        event.setStartAfterSeconds(120L);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.of(event));
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of());

        adapter.receive(snapshot, session());

        assertEquals(DrEvent.CancellationType.EXPLICIT, event.getCancellationType());
        assertEquals(DrEvent.ExecutionStatus.CANCEL_PENDING, event.getExecutionStatus());
        verify(transportService).send(any(), any(), any());
    }

    private OadrDistributeEventType emptySnapshot() {
        OadrDistributeEventType snapshot = new OadrDistributeEventType();
        snapshot.setRequestID("snapshot-2");
        snapshot.setVtnID("VTN-1");
        return snapshot;
    }

    private DrEvent knownEvent(DrEvent.ExecutionStatus executionStatus) {
        DrEvent event = new DrEvent();
        event.setEventId("Event_939393");
        event.setModificationNumber(0);
        event.setStatus(DrEvent.EventStatus.FAR);
        event.setVtnStatus(DrEvent.EventStatus.FAR);
        event.setExecutionStatus(executionStatus);
        event.setOptType(DrEvent.OptType.OPT_IN);
        event.setRequestedStartTime(Instant.parse("2026-08-11T12:00:00Z"));
        event.setStartTime(Instant.parse("2026-08-11T12:00:00Z"));
        event.setStartAfterSeconds(0L);
        event.setRandomOffsetSeconds(0L);
        event.setLastAppliedInterval(executionStatus == DrEvent.ExecutionStatus.APPLIED ? 0 : -1);
        return event;
    }

    private com.qcharge.openadr.service.session.OpenAdrSessionSnapshot session() {
        return TestSessionFixtures.registeredSession("VEN-1", "VTN-1", "REG-1");
    }
}
