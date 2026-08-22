package com.qcharge.openadr.validation;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.CommonIdValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

class CommonIdValidatorTest {

    @Test
    void responseVenIdMustMatchActiveRegistration() {
        OadrCreatedEventType request = new OadrCreatedEventType();
        OadrResponseType response = new OadrResponseType();
        response.setEiResponse(ValidatorTestSupport.eiResponse("REQ-1"));
        response.setVenID("OTHER-VEN");

        CommonIdValidator validator = new CommonIdValidator();
        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CREATED_EVENT,
                        registeredSession("ACTIVE-VEN", null, "REG-1"),
                        request,
                        response
                ))
        );

        assertEquals(OpenADRResponseCode.INVALID_ID, exception.getResponseCode());
        assertEquals("REQ-1", exception.getRequestId());
    }
}
