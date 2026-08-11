package com.qcharge.openadr.eievent;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.TestSessionFixtures;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.integration.ocpp.OcppIntegrationService;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.EventStatusEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.DrEventHandler;
import com.qcharge.openadr.service.event.EventOptDecisionService;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrEventVersioningTest extends AbstractOadrTest {

    @Mock
    private DrEventRepository repository;
    @Mock
    private VtnTransportService transportService;
    @Mock
    private OcppIntegrationService ocppIntegrationService;
    @Mock
    private OpenAdrSessionProvider sessionProvider;
    private DrEventHandler handler;

    @BeforeEach
    void setUp() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setResourceId("RES_123");
        handler = new DrEventHandler(
                properties,
                repository,
                transportService,
                new EventOptDecisionService(),
                new EventValidationService(properties),
                ocppIntegrationService,
                sessionProvider
        );
    }

    @Test
    void newEventWithNonZeroModificationNumberReturns450()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        distributeEvent.getOadrEvent().getFirst().getEiEvent()
                .getEventDescriptor().setModificationNumber(2);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.empty());

        handler.handle(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        assertResponseCode(ApplicationLayerErrorCodes.OUT_OF_SEQUENCE);
        verify(repository, never()).save(any());
        verify(ocppIntegrationService, never()).applySignal(any(), any());
    }

    @Test
    void repeatedVersionRespondsButDoesNotRepeatSideEffects()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        DrEvent existing = existingEvent(0);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.of(existing));

        handler.handle(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        assertResponseCode(ApplicationLayerErrorCodes.OK);
        verify(repository, never()).save(any());
        verify(ocppIntegrationService, never()).applySignal(any(), any());
    }

    @Test
    void anyHigherModificationNumberReplacesKnownVersion()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        distributeEvent.getOadrEvent().getFirst().getEiEvent()
                .getEventDescriptor().setModificationNumber(2);
        when(repository.findByEventId("Event_939393"))
                .thenReturn(Optional.of(existingEvent(0)));

        handler.handle(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        assertResponseCode(ApplicationLayerErrorCodes.OK);
        verify(repository).save(any());
        verify(ocppIntegrationService).applySignal(eq("Event_939393"), any());
    }

    @Test
    void lowerModificationNumberReturns450()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        when(repository.findByEventId("Event_939393"))
                .thenReturn(Optional.of(existingEvent(1)));

        handler.handle(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        assertResponseCode(ApplicationLayerErrorCodes.OUT_OF_SEQUENCE);
        verify(repository, never()).save(any());
        verify(ocppIntegrationService, never()).applySignal(any(), any());
    }

    @Test
    void cancellationForUnknownEventIsAcknowledgedWithoutPersistingOrClearing()
            throws Oadr20bUnmarshalException {
        OadrDistributeEventType distributeEvent = loadEvent();
        var descriptor = distributeEvent.getOadrEvent().getFirst().getEiEvent().getEventDescriptor();
        descriptor.setModificationNumber(5);
        descriptor.setEventStatus(EventStatusEnumeratedType.CANCELLED);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.empty());

        handler.handle(distributeEvent, TestSessionFixtures.registeredSession(
                "VEN-1", "VTN-1", "REG-1"
        ));

        EventResponse response = capturedResponse();
        assertEquals(String.valueOf(ApplicationLayerErrorCodes.OK), response.getResponseCode());
        assertEquals(OptTypeType.OPT_IN, response.getOptType());
        verify(repository, never()).save(any());
        verify(ocppIntegrationService, never()).clearEvent(any());
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
        event.setStatus(DrEvent.EventStatus.FAR);
        event.setOptType(DrEvent.OptType.OPT_IN);
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
