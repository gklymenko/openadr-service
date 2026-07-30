package com.qcharge.openadr.validation;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.RegistrationValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistrationValidatorTest {

    private final VenRegistrationRepository repository =
            mock(VenRegistrationRepository.class);
    private final OpenAdrProperties properties = new OpenAdrProperties();

    private RegistrationValidator validator;

    @BeforeEach
    void setUp() {
        properties.getVtn().setId("VTN-1");
        validator = new RegistrationValidator(properties, repository);
    }

    @Test
    void queryForUnregisteredVenRejectsReturnedRegistrationIds() {
        when(repository.findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus.REGISTERED))
                .thenReturn(Optional.empty());

        OadrQueryRegistrationType request = new OadrQueryRegistrationType();
        request.setRequestID("REQ-1");
        OadrCreatedPartyRegistrationType response =
                ValidatorTestSupport.registrationResponse("REQ-1");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.QUERY_REGISTRATION,
                        request,
                        response
                ))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void queryForRegisteredVenAllowsMatchingOptionalIds() {
        VenRegistration registration = new VenRegistration();
        registration.setVenId("VEN-1");
        registration.setRegistrationId("REG-1");
        when(repository.findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus.REGISTERED))
                .thenReturn(Optional.of(registration));

        OadrQueryRegistrationType request = new OadrQueryRegistrationType();
        request.setRequestID("REQ-1");

        assertDoesNotThrow(() -> validator.validate(new OpenAdrExchangeContext<>(
                OpenAdrOperations.QUERY_REGISTRATION,
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
