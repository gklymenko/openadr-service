package com.qcharge.openadr.transport;

import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.transport.OpenAdrOperation;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.OpenAdrReply;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.qcharge.openadr.exceptions.OpenADRResponseCode.INVALID_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAdrReplyFactoryTest {

    private static final String REQUEST_ID = "request-42";
    private static final String VEN_ID = "ven-1";
    private static final String DESCRIPTION = "Invalid identifier";

    private final OpenAdrReplyFactory factory = new OpenAdrReplyFactory();

    @Test
    void distributeEvent_mapsToCreatedEvent() {
        OadrDistributeEventType request = new OadrDistributeEventType();

        OpenAdrReply<?, ?> reply = create(request);

        assertSame(OpenAdrOperations.CREATED_EVENT, reply.operation());
        OadrCreatedEventType payload = (OadrCreatedEventType) reply.payload();
        assertEquals(VEN_ID, payload.getEiCreatedEvent().getVenID());
        assertError(payload.getEiCreatedEvent().getEiResponse());
    }

    @Test
    void reportRequests_mapToMatchingReplies() {
        List<Mapping> mappings = List.of(
                new Mapping(
                        new OadrCreateReportType(),
                        OpenAdrOperations.CREATED_REPORT_RESPONSE,
                        OadrCreatedReportType.class
                ),
                new Mapping(
                        new OadrRegisterReportType(),
                        OpenAdrOperations.REGISTERED_REPORT_RESPONSE,
                        OadrRegisteredReportType.class
                ),
                new Mapping(
                        new OadrCancelReportType(),
                        OpenAdrOperations.CANCELED_REPORT_RESPONSE,
                        OadrCanceledReportType.class
                ),
                new Mapping(
                        new OadrUpdateReportType(),
                        OpenAdrOperations.UPDATED_REPORT_RESPONSE,
                        OadrUpdatedReportType.class
                )
        );

        for (Mapping mapping : mappings) {
            OpenAdrReply<?, ?> reply = create(mapping.request());

            assertSame(mapping.operation(), reply.operation());
            assertTrue(mapping.payloadType().isInstance(reply.payload()));
            assertError(eiResponseOf(reply.payload()));
        }
    }

    @Test
    void optReplies_echoOptId() {
        OadrCreateOptType createOpt = new OadrCreateOptType();
        createOpt.setOptID("opt-1");
        OadrCancelOptType cancelOpt = new OadrCancelOptType();
        cancelOpt.setOptID("opt-2");

        OpenAdrReply<?, ?> createdReply = create(createOpt);
        OpenAdrReply<?, ?> canceledReply = create(cancelOpt);

        assertSame(OpenAdrOperations.CREATED_OPT_RESPONSE, createdReply.operation());
        assertEquals("opt-1", ((OadrCreatedOptType) createdReply.payload()).getOptID());
        assertSame(OpenAdrOperations.CANCELED_OPT_RESPONSE, canceledReply.operation());
        assertEquals("opt-2", ((OadrCanceledOptType) canceledReply.payload()).getOptID());
    }

    @Test
    void cancelRegistration_echoesRegistrationAndVenIds() {
        OadrCancelPartyRegistrationType request =
                new OadrCancelPartyRegistrationType();
        request.setRegistrationID("registration-1");

        OpenAdrReply<?, ?> reply = create(request);

        assertSame(
                OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE,
                reply.operation()
        );
        OadrCanceledPartyRegistrationType payload =
                (OadrCanceledPartyRegistrationType) reply.payload();
        assertEquals("registration-1", payload.getRegistrationID());
        assertEquals(VEN_ID, payload.getVenID());
        assertError(payload.getEiResponse());
    }

    @Test
    void unsupportedPayload_hasNoReply() {
        assertFalse(factory.createApplicationErrorReply(
                new Object(),
                VEN_ID,
                error()
        ).isPresent());
    }

    private OpenAdrReply<?, ?> create(Object request) {
        return factory.createApplicationErrorReply(request, VEN_ID, error())
                .orElseThrow();
    }

    private OpenAdrApplicationException error() {
        return new OpenAdrApplicationException(
                DESCRIPTION,
                INVALID_ID,
                DESCRIPTION,
                REQUEST_ID
        );
    }

    private void assertError(EiResponseType response) {
        assertEquals(String.valueOf(INVALID_ID), response.getResponseCode());
        assertEquals(REQUEST_ID, response.getRequestID());
        assertEquals(DESCRIPTION, response.getResponseDescription());
    }

    private EiResponseType eiResponseOf(Object payload) {
        return switch (payload) {
            case OadrCreatedReportType value -> value.getEiResponse();
            case OadrRegisteredReportType value -> value.getEiResponse();
            case OadrCanceledReportType value -> value.getEiResponse();
            case OadrUpdatedReportType value -> value.getEiResponse();
            default -> throw new IllegalArgumentException(
                    "Unsupported test payload " + payload.getClass().getName()
            );
        };
    }

    private record Mapping(
            Object request,
            OpenAdrOperation<?, ?> operation,
            Class<?> payloadType
    ) {
    }
}
