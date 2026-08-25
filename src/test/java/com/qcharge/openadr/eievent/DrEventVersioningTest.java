package com.qcharge.openadr.eievent;

import com.qcharge.openadr.model.enums.event.EventOptType;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.TestSessionFixtures;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.EventStatusEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventPolicyService;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.resource.EventResourceResolver;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedEventTarget;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.qcharge.openadr.support.EventProtocolTestComponents.protocolAdapter;

@ExtendWith(MockitoExtension.class)
class DrEventVersioningTest extends AbstractOadrTest {

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
        ResolvedResource resource = new ResolvedResource(
                10, "CP-1", "uuid-1", "RES_123", 22_000L
        );
        ResolvedEventTarget target = new ResolvedEventTarget(java.util.List.of(resource));
        org.mockito.Mockito.lenient()
                .when(eventResourceResolver.resolveEventTarget(any(), any())).thenReturn(target);
        org.mockito.Mockito.lenient()
                .when(eventResourceResolver.resolveSignalTargets(any(), eq(target)))
                .thenReturn(java.util.Map.of(
                        "SIG_01", java.util.List.of(resource),
                        "SIG_02", java.util.List.of(resource)
                ));
        adapter = protocolAdapter(
                repository,
                transportService,
                new EventPolicyService(properties),
                eventResourceResolver
        );
    }

    @Test
    void repeatedVersionRespondsButDoesNotRepeatSideEffects()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        DrEvent existing = existingEvent(0);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.of(existing));

        adapter.receive(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        assertResponseCode(OpenADRResponseCode.OK);
        verify(repository, never()).save(any());
    }

    @Test
    void anyHigherModificationNumberReplacesKnownVersion()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        distributeEvent.getOadrEvent().getFirst().getEiEvent()
                .getEventDescriptor().setModificationNumber(2);
        when(repository.findByEventId("Event_939393"))
                .thenReturn(Optional.of(existingEvent(0)));

        adapter.receive(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        assertResponseCode(OpenADRResponseCode.OK);
        verify(repository).save(any());
    }

    @Test
    void modificationKeepsRandomOffsetWhenStartAfterIsUnchanged()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        distributeEvent.getOadrEvent().getFirst().getEiEvent()
                .getEventDescriptor().setModificationNumber(1);
        DrEvent existing = existingEvent(0);
        existing.setStartAfterSeconds(180L);
        existing.setRandomOffsetSeconds(73L);
        existing.setRequestedStartTime(Instant.parse("2001-12-17T09:35:47Z"));
        existing.setStartTime(existing.getRequestedStartTime().plusSeconds(73L));
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.of(existing));

        adapter.receive(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        ArgumentCaptor<DrEvent> captor = ArgumentCaptor.forClass(DrEvent.class);
        verify(repository).save(captor.capture());
        DrEvent saved = captor.getValue();
        assertEquals(73L, saved.getRandomOffsetSeconds());
        assertEquals(
                saved.getRequestedStartTime().plusSeconds(73L),
                saved.getStartTime()
        );
    }

    @Test
    void lowerModificationNumberReturns450()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        when(repository.findByEventId("Event_939393"))
                .thenReturn(Optional.of(existingEvent(1)));

        adapter.receive(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        assertResponseCode(OpenADRResponseCode.OUT_OF_SEQUENCE);
        verify(repository, never()).save(any());
    }

    @Test
    void cancellationForUnknownEventIsAcknowledgedWithoutPersistingOrClearing()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        var descriptor = distributeEvent.getOadrEvent().getFirst().getEiEvent().getEventDescriptor();
        descriptor.setModificationNumber(5);
        descriptor.setEventStatus(EventStatusEnumeratedType.CANCELLED);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.empty());

        adapter.receive(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        EventResponse response = capturedResponse();
        assertEquals(String.valueOf(OpenADRResponseCode.OK), response.getResponseCode());
        assertEquals(OptTypeType.OPT_IN, response.getOptType());
        verify(repository, never()).save(any());
    }

    private OadrDistributeEventType loadEvent() throws Oadr20bUnmarshalException {
        return jaxbContext.unmarshal(
                new File(EIEVENT_PATH + "oadrDistributeEvent.xml"),
                OadrDistributeEventType.class
        );
    }

    private DrEvent existingEvent(int modificationNumber) {
        DrEvent event = new DrEvent();
        event.setEventId("Event_939393");
        event.setModificationNumber(modificationNumber);
        event.setVenStatus(EventStatus.FAR);
        event.setOptType(EventOptType.OPT_IN);
        return event;
    }

    private void assertResponseCode(int expectedCode) {
        assertEquals(String.valueOf(expectedCode), capturedResponse().getResponseCode());
    }

    private EventResponse capturedResponse() {
        ArgumentCaptor<OadrCreatedEventType> captor =
                ArgumentCaptor.forClass(OadrCreatedEventType.class);
        verify(transportService).send(
                eq(OpenAdrOperations.CREATED_EVENT),
                captor.capture(),
                any()
        );
        EventResponse response = captor.getValue().getEiCreatedEvent()
                .getEventResponses().getEventResponse().getFirst();
        return response;
    }
}
