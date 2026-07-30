package com.qcharge.openadr.validation;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.CommonIdValidator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommonIdValidatorTest {

    @Test
    void responseVenIdMustMatchActiveRegistration() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getVen().setId("CONFIGURED-VEN");

        VenRegistration registration = new VenRegistration();
        registration.setVenId("ACTIVE-VEN");

        VenRegistrationRepository repository = mock(VenRegistrationRepository.class);
        when(repository.findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus.REGISTERED))
                .thenReturn(Optional.of(registration));

        OadrCreatedEventType request = new OadrCreatedEventType();
        OadrResponseType response = new OadrResponseType();
        response.setEiResponse(ValidatorTestSupport.eiResponse("REQ-1"));
        response.setVenID("OTHER-VEN");

        CommonIdValidator validator = new CommonIdValidator(properties, repository);
        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CREATED_EVENT,
                        request,
                        response
                ))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }
}
