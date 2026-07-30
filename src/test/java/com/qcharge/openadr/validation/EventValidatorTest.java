package com.qcharge.openadr.validation;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.ei.EiEventType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.ResponseRequiredType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.EventValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

class EventValidatorTest {

    private final OpenAdrProperties properties = new OpenAdrProperties();
    private EventValidator validator;

    @BeforeEach
    void setUp() {
        properties.getVtn().setId("VTN-1");
        validator = new EventValidator();
    }

    @Test
    void distributeEventRejectsUnexpectedVtnId() {
        OadrDistributeEventType response = distributeEvent("EVENT-1");
        response.setVtnID("OTHER");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(context(response))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void perEventValidationDoesNotRejectWholeDistributePayload() {
        OadrDistributeEventType response = distributeEvent("EVENT-1");
        response.getOadrEvent().add(event("EVENT-1"));

        assertDoesNotThrow(() -> validator.validate(context(response)));
    }

    @Test
    void unknownCanceledEventIdIsNotRejectedAsUnknown() {
        OadrDistributeEventType response = distributeEvent("UNKNOWN-CANCELED");
        response.getOadrEvent().getFirst().getEiEvent().getEventDescriptor()
                .setEventStatus(com.qcharge.openadr.model.oadr20b.ei.EventStatusEnumeratedType.CANCELLED);

        assertDoesNotThrow(() -> validator.validate(context(response)));
    }

    private OpenAdrExchangeContext<OadrPollType, Object> context(
            OadrDistributeEventType response
    ) {
        OadrPollType request = new OadrPollType();
        request.setVenID("VEN-1");
        return new OpenAdrExchangeContext<>(
                OpenAdrOperations.POLL,
                registeredSession("VEN-1", "VTN-1", "REG-1"),
                request,
                response
        );
    }

    private OadrDistributeEventType distributeEvent(String eventId) {
        OadrDistributeEventType response = new OadrDistributeEventType();
        response.setEiResponse(ValidatorTestSupport.eiResponse(""));
        response.setRequestID("DIST-1");
        response.setVtnID("VTN-1");
        response.getOadrEvent().add(event(eventId));
        return response;
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
