package com.qcharge.openadr.validation;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.OptValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

class OptValidatorTest {

    private final OptValidator validator = new OptValidator();

    @Test
    void createdOptMustEchoRequestId() {
        OadrCreateOptType request = request();
        OadrCreatedOptType response = response();
        response.getEiResponse().setRequestID("OTHER");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CREATE_OPT,
                        registeredSession(),
                        request,
                        response
                ))
        );

        assertEquals(OpenADRResponseCode.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void createdOptMustReturnRequestedOptId() {
        OadrCreateOptType request = request();
        OadrCreatedOptType response = response();
        response.setOptID("OTHER");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CREATE_OPT,
                        registeredSession(),
                        request,
                        response
                ))
        );

        assertEquals(OpenADRResponseCode.INVALID_ID, exception.getResponseCode());
    }

    private OadrCreateOptType request() {
        OadrCreateOptType request = new OadrCreateOptType();
        request.setRequestID("REQ-1");
        request.setOptID("OPT-1");
        return request;
    }

    private OadrCreatedOptType response() {
        OadrCreatedOptType response = new OadrCreatedOptType();
        response.setEiResponse(ValidatorTestSupport.eiResponse("REQ-1"));
        response.setOptID("OPT-1");
        return response;
    }
}
