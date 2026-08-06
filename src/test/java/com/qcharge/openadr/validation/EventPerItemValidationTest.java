package com.qcharge.openadr.validation;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.integration.ocpp.OcppIntegrationService;
import com.qcharge.openadr.model.oadr20b.ei.EiEventType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.ResponseRequiredType;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.DrEventHandler;
import com.qcharge.openadr.service.event.EventOptDecisionService;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventPerItemValidationTest {

    private final DrEventRepository repository = mock(DrEventRepository.class);
    private final VtnTransportService transportService = mock(VtnTransportService.class);
    private final EventValidationService validationService = mock(EventValidationService.class);
    private final OpenAdrSessionProvider sessionProvider = mock(OpenAdrSessionProvider.class);

    private DrEventHandler handler;

    @BeforeEach
    void setUp() {
        when(sessionProvider.current())
                .thenReturn(com.qcharge.openadr.TestSessionFixtures.registeredSession(
                        "VEN-1", "VTN-1", "REG-1"
                ));
        when(repository.findByEventId("EVENT-1")).thenReturn(Optional.empty());
        when(validationService.parseSignal(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());

        handler = new DrEventHandler(
                new OpenAdrProperties(),
                repository,
                transportService,
                mock(EventOptDecisionService.class),
                validationService,
                mock(OcppIntegrationService.class),
                sessionProvider
        );
    }

    @Test
    void duplicateEventIdProducesPerEvent452WithoutRejectingWholePayload() {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");
        distributeEvent.getOadrEvent().add(event("EVENT-1"));
        distributeEvent.getOadrEvent().add(event("EVENT-1"));

        handler.handle(distributeEvent);

        List<EventResponse> responses = capturedResponses();
        assertEquals(2, responses.size());
        assertEquals(
                String.valueOf(ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED),
                responses.get(0).getResponseCode()
        );
        assertEquals(
                String.valueOf(ApplicationLayerErrorCodes.INVALID_ID),
                responses.get(1).getResponseCode()
        );
    }

    @Test
    void missingEventDescriptorProducesPerEvent459() {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");

        OadrDistributeEventType.OadrEvent invalid =
                new OadrDistributeEventType.OadrEvent();
        invalid.setOadrResponseRequired(ResponseRequiredType.ALWAYS);
        distributeEvent.getOadrEvent().add(invalid);

        handler.handle(distributeEvent);

        List<EventResponse> responses = capturedResponses();
        assertEquals(1, responses.size());
        assertEquals(
                String.valueOf(ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER),
                responses.getFirst().getResponseCode()
        );
    }

    @Test
    void createdEventCorrelatesThroughEventResponseRequestId() {
        OadrDistributeEventType distributeEvent = new OadrDistributeEventType();
        distributeEvent.setRequestID("DIST-1");
        distributeEvent.setVtnID("VTN-1");
        distributeEvent.getOadrEvent().add(event("EVENT-1"));

        handler.handle(distributeEvent);

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

    private List<EventResponse> capturedResponses() {
        return capturedCreatedEvent()
                .getEiCreatedEvent()
                .getEventResponses()
                .getEventResponse();
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

    private OadrDistributeEventType.OadrEvent event(String eventId) {
        EventDescriptorType descriptor = new EventDescriptorType();
        descriptor.setEventID(eventId);

        EiEventType eiEvent = new EiEventType();
        eiEvent.setEventDescriptor(descriptor);

        OadrDistributeEventType.OadrEvent event =
                new OadrDistributeEventType.OadrEvent();
        event.setEiEvent(eiEvent);
        event.setOadrResponseRequired(ResponseRequiredType.ALWAYS);
        return event;
    }
}
