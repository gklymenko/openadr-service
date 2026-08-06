package com.qcharge.openadr.validation;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.ei.ReportSpecifierType;
import com.qcharge.openadr.model.oadr20b.ei.SpecifierPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportDescriptionType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.ReportValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

class ReportValidatorTest {

    private final ReportValidator validator = new ReportValidator();

    @Test
    void registeredReportMustEchoRequestId() {
        OadrRegisterReportType request = request();
        OadrRegisteredReportType response = response("OTHER", "RID-1");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.REGISTER_REPORT,
                        registeredSession(),
                        request,
                        response
                ))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void registeredReportRejectsRidNotOfferedByMetadata() {
        OadrRegisterReportType request = request();
        OadrRegisteredReportType response = response("REQ-1", "RID-OTHER");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.REGISTER_REPORT,
                        registeredSession(),
                        request,
                        response
                ))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void reportResponseAcknowledgementsAllowEmptyRequestId() {
        acknowledgementContexts("").forEach(context ->
                assertDoesNotThrow(() -> validator.validate(context))
        );
    }

    @Test
    void reportResponseAcknowledgementsDoNotEchoOriginalRequestId() {
        acknowledgementContexts("ACK-OTHER").forEach(context ->
                assertDoesNotThrow(() -> validator.validate(context))
        );
    }

    @Test
    void reportResponseAcknowledgementStillRequiresEiResponse() {
        OadrRegisteredReportType request = new OadrRegisteredReportType();
        request.setEiResponse(ValidatorTestSupport.eiResponse("INBOUND-REGISTER"));

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.REGISTERED_REPORT_RESPONSE,
                        registeredSession(),
                        request,
                        new OadrResponseType()
                ))
        );

        assertEquals(
                ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER,
                exception.getResponseCode()
        );
    }

    @Test
    void reportResponseAcknowledgementStillValidatesVenId() {
        OadrRegisteredReportType request = new OadrRegisteredReportType();
        request.setEiResponse(ValidatorTestSupport.eiResponse("INBOUND-REGISTER"));

        OadrResponseType acknowledgement = acknowledgement("");
        acknowledgement.setVenID("OTHER-VEN");

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validate(new OpenAdrExchangeContext<>(
                        OpenAdrOperations.REGISTERED_REPORT_RESPONSE,
                        registeredSession(),
                        request,
                        acknowledgement
                ))
        );

        assertEquals(ApplicationLayerErrorCodes.INVALID_ID, exception.getResponseCode());
    }

    private OadrRegisterReportType request() {
        OadrReportDescriptionType description = new OadrReportDescriptionType();
        description.setRID("RID-1");

        OadrReportType report = new OadrReportType();
        report.setReportSpecifierID("SPEC-1");
        report.getOadrReportDescription().add(description);

        OadrRegisterReportType request = new OadrRegisterReportType();
        request.setRequestID("REQ-1");
        request.getOadrReport().add(report);
        return request;
    }

    private OadrRegisteredReportType response(String requestId, String rid) {
        SpecifierPayloadType payload = new SpecifierPayloadType();
        payload.setRID(rid);

        ReportSpecifierType specifier = new ReportSpecifierType();
        specifier.setReportSpecifierID("SPEC-1");
        specifier.getSpecifierPayload().add(payload);

        OadrReportRequestType reportRequest = new OadrReportRequestType();
        reportRequest.setReportRequestID("REPORT-REQ-1");
        reportRequest.setReportSpecifier(specifier);

        OadrRegisteredReportType response = new OadrRegisteredReportType();
        response.setEiResponse(ValidatorTestSupport.eiResponse(requestId));
        response.getOadrReportRequest().add(reportRequest);
        return response;
    }

    private List<OpenAdrExchangeContext<?, ?>> acknowledgementContexts(
            String acknowledgementRequestId
    ) {
        OadrRegisteredReportType registeredReport = new OadrRegisteredReportType();
        registeredReport.setEiResponse(ValidatorTestSupport.eiResponse("INBOUND-REGISTER"));

        OadrCreatedReportType createdReport = new OadrCreatedReportType();
        createdReport.setEiResponse(ValidatorTestSupport.eiResponse("INBOUND-CREATE"));

        OadrUpdatedReportType updatedReport = new OadrUpdatedReportType();
        updatedReport.setEiResponse(ValidatorTestSupport.eiResponse("INBOUND-UPDATE"));

        OadrCanceledReportType canceledReport = new OadrCanceledReportType();
        canceledReport.setEiResponse(ValidatorTestSupport.eiResponse("INBOUND-CANCEL"));

        return List.of(
                new OpenAdrExchangeContext<>(
                        OpenAdrOperations.REGISTERED_REPORT_RESPONSE,
                        registeredSession(),
                        registeredReport,
                        acknowledgement(acknowledgementRequestId)
                ),
                new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CREATED_REPORT_RESPONSE,
                        registeredSession(),
                        createdReport,
                        acknowledgement(acknowledgementRequestId)
                ),
                new OpenAdrExchangeContext<>(
                        OpenAdrOperations.UPDATED_REPORT_RESPONSE,
                        registeredSession(),
                        updatedReport,
                        acknowledgement(acknowledgementRequestId)
                ),
                new OpenAdrExchangeContext<>(
                        OpenAdrOperations.CANCELED_REPORT_RESPONSE,
                        registeredSession(),
                        canceledReport,
                        acknowledgement(acknowledgementRequestId)
                )
        );
    }

    private OadrResponseType acknowledgement(String requestId) {
        OadrResponseType response = new OadrResponseType();
        response.setEiResponse(ValidatorTestSupport.eiResponse(requestId));
        response.setVenID("TH_VEN");
        return response;
    }
}
