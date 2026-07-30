package com.qcharge.openadr.validation;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.RegistrationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.qcharge.openadr.TestSessionFixtures.bootstrapSession;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

class RegistrationValidatorTest {

    private RegistrationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new RegistrationValidator();
    }

    @Test
    void queryForUnregisteredVenRejectsReturnedRegistrationIds() {
        OadrQueryRegistrationType request = new OadrQueryRegistrationType();
        request.setRequestID("REQ-1");
        OadrCreatedPartyRegistrationType response =
                ValidatorTestSupport.registrationResponse("REQ-1");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.QUERY_REGISTRATION,
                        bootstrapSession("VEN-1", "VTN-1"),
                        request,
                        response
                ))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void queryForRegisteredVenAllowsMatchingOptionalIds() {
        OadrQueryRegistrationType request = new OadrQueryRegistrationType();
        request.setRequestID("REQ-1");

        assertDoesNotThrow(() -> validator.validate(new OpenAdrExchangeContext<>(
                OpenAdrOperations.QUERY_REGISTRATION,
                registeredSession("VEN-1", "VTN-1", "REG-1"),
                request,
                ValidatorTestSupport.registrationResponse("REQ-1")
        )));
    }

    @Test
    void pullRegistrationRequiresPositivePollFrequency() {
        OadrCreatePartyRegistrationType request = createRegistrationRequest();
        OadrCreatedPartyRegistrationType response =
                ValidatorTestSupport.registrationResponse("REQ-1");
        response.setOadrRequestedOadrPollFreq(null);

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CREATE_PARTY_REGISTRATION,
                        bootstrapSession("VEN-1", "VTN-1"),
                        request,
                        response
                ))
        );

        assertEquals(
                ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER,
                exception.getResponseCode()
        );
    }

    @Test
    void reregistrationRequiresSameRegistrationId() {
        OadrCreatePartyRegistrationType request = createRegistrationRequest();
        request.setRegistrationID("REG-OLD");
        OadrCreatedPartyRegistrationType response =
                ValidatorTestSupport.registrationResponse("REQ-1");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CREATE_PARTY_REGISTRATION,
                        registeredSession("VEN-1", "VTN-1", "REG-OLD"),
                        request,
                        response
                ))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    private OadrCreatePartyRegistrationType createRegistrationRequest() {
        OadrCreatePartyRegistrationType request = new OadrCreatePartyRegistrationType();
        request.setRequestID("REQ-1");
        request.setVenID("VEN-1");
        request.setOadrProfileName("2.0b");
        request.setOadrTransportName(OadrTransportType.SIMPLE_HTTP);
        request.setOadrHttpPullModel(true);
        return request;
    }
}
