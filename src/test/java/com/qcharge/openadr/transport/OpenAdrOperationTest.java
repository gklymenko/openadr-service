package com.qcharge.openadr.transport;

import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperation;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.ResponseBodyPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAdrOperationTest {

    @Test
    void queryRegistration_definesExplicitExchangeContract() {
        var operation = OpenAdrOperations.QUERY_REGISTRATION;

        assertEquals("queryRegistration", operation.name());
        assertEquals(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, operation.endpoint());
        assertEquals(OadrQueryRegistrationType.class, operation.requestType());
        assertEquals(
                Set.of(OadrCreatedPartyRegistrationType.class),
                operation.responseTypes()
        );
        assertFalse(operation.allowsEmptyResponse());
    }

    @Test
    void responseOperation_allowsEmptyBody() {
        assertTrue(OpenAdrOperations.CREATED_EVENT.allowsEmptyResponse());
    }

    @Test
    void requireValidRequest_rejectsPayloadFromAnotherOperation() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> OpenAdrOperations.QUERY_REGISTRATION.requireValidRequest(new Object())
        );

        assertTrue(exception.getMessage().contains("queryRegistration"));
        assertTrue(exception.getMessage().contains("OadrQueryRegistrationType"));
    }

    @Test
    void exchangeContext_keepsOperationRequestAndResponseTogether() {
        OadrQueryRegistrationType request = Oadr20bEiRegisterPartyBuilders
                .newOadr20bQueryRegistrationBuilder("request-1")
                .build();
        OadrCreatedPartyRegistrationType response = new OadrCreatedPartyRegistrationType();

        OpenAdrExchangeContext<
                OadrQueryRegistrationType,
                OadrCreatedPartyRegistrationType
                > context = new OpenAdrExchangeContext<>(
                OpenAdrOperations.QUERY_REGISTRATION,
                request,
                response
        );

        assertSame(OpenAdrOperations.QUERY_REGISTRATION, context.operation());
        assertSame(request, context.request());
        assertSame(response, context.response());
    }

    @Test
    void customOperation_defensivelyCopiesResponseTypes() {
        Class<OadrCreatedPartyRegistrationType> responseType =
                OadrCreatedPartyRegistrationType.class;
        Set<Class<? extends OadrCreatedPartyRegistrationType>> responseTypes =
                new java.util.HashSet<>(Set.of(responseType));

        OpenAdrOperation<OadrQueryRegistrationType, OadrCreatedPartyRegistrationType>
                operation = new OpenAdrOperation<>(
                "test",
                "/test",
                OadrQueryRegistrationType.class,
                responseTypes,
                ResponseBodyPolicy.REQUIRED
        );

        responseTypes.clear();

        assertEquals(Set.of(responseType), operation.responseTypes());
    }
}
