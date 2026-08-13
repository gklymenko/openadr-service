package com.qcharge.openadr.eievent;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.TestSessionFixtures;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventOptDecisionService;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.resource.EventResourceResolver;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedEventTarget;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.Optional;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.qcharge.openadr.support.EventProtocolTestComponents.protocolAdapter;

class EventProtocolAdapterPersistenceTest extends AbstractOadrTest {

    @Test
    void handle_persistsCompleteSignalAndIntervalPlan() throws Oadr20bUnmarshalException {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setResourceId("RES_123");

        DrEventRepository repository = mock(DrEventRepository.class);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.empty());
        EventResourceResolver resolver = mock(EventResourceResolver.class);
        ResolvedResource resolved = new ResolvedResource(
                10, "CP-1", "uuid-1", "RES_123", 22_000L
        );
        ResolvedEventTarget eventTarget = new ResolvedEventTarget(java.util.List.of(resolved));
        when(resolver.resolveEventTarget(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(eventTarget);
        when(resolver.resolveSignalTargets(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(eventTarget)
        )).thenReturn(java.util.Map.of(
                "SIG_01", java.util.List.of(resolved),
                "SIG_02", java.util.List.of(resolved)
        ));

        EventProtocolAdapter adapter = protocolAdapter(
                repository,
                mock(VtnTransportService.class),
                new EventOptDecisionService(),
                new EventValidationService(properties),
                resolver
        );

        OadrDistributeEventType distributeEvent = jaxbContext.unmarshal(
                new File(EIEVENT_PATH + "oadrDistributeEvent.xml"),
                OadrDistributeEventType.class
        );
        distributeEvent.getOadrEvent().getFirst().getEiEvent()
                .getEventDescriptor().setTestEvent("certification-test");

        adapter.receive(
                distributeEvent,
                TestSessionFixtures.registeredSession("VEN-1", "VTN-1", "REG-1")
        );

        ArgumentCaptor<DrEvent> captor = ArgumentCaptor.forClass(DrEvent.class);
        verify(repository).save(captor.capture());
        DrEvent saved = captor.getValue();

        assertEquals(2, saved.getSignals().size());
        assertEquals(1, saved.getResources().size());
        assertEquals("RES_123", saved.getResources().getFirst().getResourceId());
        assertSame(saved, saved.getResources().getFirst().getEvent());
        assertTrue(saved.isTestEvent());
        assertEquals(DrEvent.ExecutionStatus.SCHEDULED, saved.getExecutionStatus());
        assertEquals(Instant.parse("2001-12-17T09:40:47Z"), saved.getRequestedStartTime());
        assertEquals(180L, saved.getStartAfterSeconds());
        assertEquals(
                saved.getRequestedStartTime().plusSeconds(saved.getRandomOffsetSeconds()),
                saved.getStartTime()
        );
        assertEquals(300L, saved.getRampUpSeconds());
        assertEquals(300L, saved.getRecoverySeconds());
        assertEquals("SIG_01", saved.getSignals().getFirst().getSignalId());
        assertEquals(false, saved.getSignals().getFirst().isSelectedForExecution());
        assertEquals(true, saved.getSignals().get(1).isSelectedForExecution());
        assertEquals(2, saved.getSignals().getFirst().getIntervals().size());
        assertEquals("0", saved.getSignals().getFirst().getIntervals().getFirst().getIntervalUid());
        assertEquals(900L, saved.getSignals().getFirst().getIntervals().getFirst().getDurationSeconds());
        assertSame(saved, saved.getSignals().getFirst().getEvent());
        assertSame(
                saved.getSignals().getFirst(),
                saved.getSignals().getFirst().getIntervals().getFirst().getSignal()
        );
    }
}
