package com.qcharge.openadr.transport;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.transport.ApplicationErrorAction;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorPolicy;
import com.qcharge.openadr.service.transport.OpenAdrApplicationResponseEvaluator;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAdrApplicationResponseEvaluatorTest {

    private final OpenAdrApplicationResponseEvaluator evaluator =
            new OpenAdrApplicationResponseEvaluator(
                    new OpenAdrApplicationErrorPolicy()
            );

    @Test
    void responseCode200_passes() {
        OadrResponseType response = new OadrResponseType();
        response.setEiResponse(eiResponse("200"));

        assertDoesNotThrow(
                () -> evaluator.evaluate(OpenAdrOperations.POLL, response)
        );
    }

    @ParameterizedTest
    @MethodSource("applicationErrorCodes")
    void anyNon200Code_throwsApplicationException(int responseCode) {
        OadrResponseType response = new OadrResponseType();
        response.setEiResponse(eiResponse(String.valueOf(responseCode)));

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> evaluator.evaluate(OpenAdrOperations.POLL, response)
        );

        assertEquals(responseCode, exception.getResponseCode());
        assertEquals("description-" + responseCode, exception.getResponseDescription());
        assertEquals("request-123", exception.getRequestId());
        assertEquals("poll", exception.getOperationName());
        assertEquals(
                responseCode == ApplicationLayerErrorCodes.NOT_REGISTERED
                        ? ApplicationErrorAction.REQUIRE_REREGISTRATION
                        : ApplicationErrorAction.FAIL_OPERATION,
                exception.getAction()
        );
    }

    @ParameterizedTest
    @MethodSource("responsesWithEiResponse")
    void supportsEveryInboundPayloadContainingEiResponse(
            ResponseFixture fixture
    ) {
        fixture.setter().accept(eiResponse("452"));

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> evaluator.evaluate(
                        OpenAdrOperations.POLL,
                        fixture.payload()
                )
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void perEventResponse_doesNotBecomeEnvelopeError() {
        var eventResponse = Oadr20bEiEventBuilders
                .newOadr20bCreatedEventEventResponseBuilder(
                        "event-1",
                        0,
                        "request-123",
                        ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED,
                        OptTypeType.OPT_OUT
                )
                .build();

        OadrCreatedEventType response = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(
                        Oadr20bResponseBuilders
                                .newOadr20bEiResponseOK("request-123"),
                        "ven-1"
                )
                .addEventResponse(eventResponse)
                .build();

        assertDoesNotThrow(
                () -> evaluator.evaluate(OpenAdrOperations.CREATED_EVENT, response)
        );
    }

    @Test
    void nonNumericResponseCode_becomesComplianceError() {
        OadrResponseType response = new OadrResponseType();
        response.setEiResponse(eiResponse("invalid"));

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> evaluator.evaluate(OpenAdrOperations.POLL, response)
        );

        assertEquals(
                ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER,
                exception.getResponseCode()
        );
        assertEquals(ApplicationErrorAction.FAIL_OPERATION, exception.getAction());
    }

    private EiResponseType eiResponse(String code) {
        EiResponseType response = new EiResponseType();
        response.setResponseCode(code);
        response.setResponseDescription("description-" + code);
        response.setRequestID("request-123");
        return response;
    }

    private static Stream<Integer> applicationErrorCodes() {
        return Stream.of(201, 450, 452, 454, 460, 463, 469, 499);
    }

    private static Stream<ResponseFixture> responsesWithEiResponse() {
        OadrCreatedPartyRegistrationType registration =
                new OadrCreatedPartyRegistrationType();
        OadrRegisteredReportType registeredReport =
                new OadrRegisteredReportType();
        OadrResponseType response = new OadrResponseType();
        OadrCreatedReportType createdReport = new OadrCreatedReportType();
        OadrUpdatedReportType updatedReport = new OadrUpdatedReportType();
        OadrCanceledReportType canceledReport = new OadrCanceledReportType();
        OadrCreatedOptType createdOpt = new OadrCreatedOptType();
        OadrCanceledOptType canceledOpt = new OadrCanceledOptType();
        OadrCanceledPartyRegistrationType canceledRegistration =
                new OadrCanceledPartyRegistrationType();
        OadrDistributeEventType distributeEvent =
                new OadrDistributeEventType();

        return Stream.of(
                new ResponseFixture(registration, registration::setEiResponse),
                new ResponseFixture(registeredReport, registeredReport::setEiResponse),
                new ResponseFixture(response, response::setEiResponse),
                new ResponseFixture(createdReport, createdReport::setEiResponse),
                new ResponseFixture(updatedReport, updatedReport::setEiResponse),
                new ResponseFixture(canceledReport, canceledReport::setEiResponse),
                new ResponseFixture(createdOpt, createdOpt::setEiResponse),
                new ResponseFixture(canceledOpt, canceledOpt::setEiResponse),
                new ResponseFixture(
                        canceledRegistration,
                        canceledRegistration::setEiResponse
                ),
                new ResponseFixture(distributeEvent, distributeEvent::setEiResponse)
        );
    }

    private record ResponseFixture(
            Object payload,
            Consumer<EiResponseType> setter
    ) {
    }
}
