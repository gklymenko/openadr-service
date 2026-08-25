package com.qcharge.openadr.validation;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.TestSessionFixtures;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.ResponseRequiredType;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.EventPolicyService;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.resource.EventResourceResolver;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedEventTarget;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static com.qcharge.openadr.support.EventProtocolTestComponents.protocolAdapter;

class EventPerItemValidationTest extends AbstractOadrTest {

    private final DrEventRepository repository = mock(DrEventRepository.class);
    private final VtnTransportService transportService = mock(VtnTransportService.class);
    private final EventPolicyService validationService = mock(EventPolicyService.class);
    private final EventResourceResolver eventResourceResolver = mock(EventResourceResolver.class);

    private EventProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        when(repository.findByEventId("EVENT-1")).thenReturn(Optional.empty());
        when(validationService.supportedSignals(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(validationService.selectPreferredSignal(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(eventResourceResolver.resolveEventTarget(any(), any()))
                .thenReturn(new ResolvedEventTarget(List.of()));

        adapter = protocolAdapter(
                repository,
                transportService,
                validationService,
                eventResourceResolver
        );
    }

    @Test
    void duplicateEventIdProducesPerEvent452WithoutRejectingWholePayload()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");
        distributeEvent.getOadrEvent().add(event("EVENT-1"));
        distributeEvent.getOadrEvent().add(event("EVENT-1"));

        adapter.receive(distributeEvent, session());

        List<EventResponse> responses = capturedResponses();
        assertEquals(2, responses.size());
        assertEquals(
                String.valueOf(OpenADRResponseCode.SIGNAL_NOT_SUPPORTED),
                responses.get(0).getResponseCode()
        );
        assertEquals(
                String.valueOf(OpenADRResponseCode.INVALID_ID),
                responses.get(1).getResponseCode()
        );
    }

    @Test
    void missingEiEventDoesNotProduceFabricatedEventResponse() {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");

        OadrDistributeEventType.OadrEvent invalid =
                new OadrDistributeEventType.OadrEvent();
        invalid.setOadrResponseRequired(ResponseRequiredType.ALWAYS);
        distributeEvent.getOadrEvent().add(invalid);

        adapter.receive(distributeEvent, session());

        verify(transportService, never()).send(
                eq(OpenAdrOperations.CREATED_EVENT),
                any(OadrCreatedEventType.class),
                any()
        );
    }

    @Test
    void invalidEventWithoutIdDoesNotRejectValidSiblingOrFabricateIdentity()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");
        distributeEvent.getOadrEvent().add(event(" "));
        distributeEvent.getOadrEvent().add(event("EVENT-1"));

        adapter.receive(distributeEvent, session());

        List<EventResponse> responses = capturedResponses();
        assertEquals(1, responses.size());
        assertEquals("EVENT-1", responses.getFirst().getQualifiedEventID().getEventID());
        assertEquals(
                String.valueOf(OpenADRResponseCode.SIGNAL_NOT_SUPPORTED),
                responses.getFirst().getResponseCode()
        );
        verify(repository).findByEventId("EVENT-1");
    }

    @Test
    void invalidEventWithQualifiedIdProducesCorrelatedPerEvent454()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");

        OadrDistributeEventType.OadrEvent invalid = event("EVENT-1");
        invalid.getEiEvent().getEventDescriptor().setEventStatus(null);
        distributeEvent.getOadrEvent().add(invalid);

        adapter.receive(distributeEvent, session());

        EventResponse response = capturedResponses().getFirst();
        assertEquals("EVENT-1", response.getQualifiedEventID().getEventID());
        assertEquals(0L, response.getQualifiedEventID().getModificationNumber());
        assertEquals(
                String.valueOf(OpenADRResponseCode.INVALID_DATA),
                response.getResponseCode()
        );
    }

    @Test
    void createdEventCorrelatesThroughEventResponseRequestId()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");
        distributeEvent.getOadrEvent().add(event("EVENT-1"));

        adapter.receive(distributeEvent, session());

        OadrCreatedEventType createdEvent = capturedCreatedEvent();

        assertEquals(
                "",
                createdEvent.getEiCreatedEvent().getEiResponse().getRequestID()
        );
        assertEquals(
                "DIST-1",
                createdEvent.getEiCreatedEvent()
                        .getEventResponses()
                        .getEventResponse()
                        .getFirst()
                        .getRequestID()
        );
    }

    @Test
    void unresolvedSelectedSignalReturns469AndOptOutWithoutPersistence()
            throws Oadr20bUnmarshalException {
        EventSignalCommand selected = new EventSignalCommand(
                "SIG-1", "SIMPLE", "level", null,
                null, null, null, null, List.of(), null
        );
        when(validationService.supportedSignals(any())).thenReturn(List.of(selected));
        when(validationService.selectPreferredSignal(any())).thenReturn(Optional.of(selected));
        when(eventResourceResolver.resolveSignalTargets(any(), any()))
                .thenThrow(new EventValidationException(
                        "Unable to resolve target resource",
                        OpenADRResponseCode.DEPLOYMENT_ERROR_OTHER
                ));

        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");
        distributeEvent.getOadrEvent().add(event("EVENT-1"));

        adapter.receive(distributeEvent, session());

        EventResponse response = capturedResponses().getFirst();
        assertEquals(String.valueOf(OpenADRResponseCode.DEPLOYMENT_ERROR_OTHER),
                response.getResponseCode());
        assertEquals(com.qcharge.openadr.model.oadr20b.ei.OptTypeType.OPT_OUT,
                response.getOptType());
        verify(repository, never()).save(any());
    }

    private List<EventResponse> capturedResponses() {
        return capturedCreatedEvent()
                .getEiCreatedEvent()
                .getEventResponses()
                .getEventResponse();
    }

    private com.qcharge.openadr.service.session.OpenAdrSessionSnapshot session() {
        return TestSessionFixtures.registeredSession("VEN-1", "VTN-1", "REG-1");
    }

    private OadrCreatedEventType capturedCreatedEvent() {
        ArgumentCaptor<OadrCreatedEventType> captor =
                ArgumentCaptor.forClass(OadrCreatedEventType.class);
        verify(transportService).send(
                eq(OpenAdrOperations.CREATED_EVENT),
                captor.capture(),
                any()
        );
        return captor.getValue();
    }

    private OadrDistributeEventType.OadrEvent event(String eventId)
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType payload = jaxbContext.unmarshal(
                new File(EIEVENT_PATH + "oadrDistributeEvent.xml"),
                OadrDistributeEventType.class);
        OadrDistributeEventType.OadrEvent event = payload.getOadrEvent().getFirst();
        event.getEiEvent().getEventDescriptor().setEventID(eventId);
        return event;
    }
}
