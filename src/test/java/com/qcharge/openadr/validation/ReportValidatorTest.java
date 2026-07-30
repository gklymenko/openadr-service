package com.qcharge.openadr.validation;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.ei.ReportSpecifierType;
import com.qcharge.openadr.model.oadr20b.ei.SpecifierPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportDescriptionType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.validation.ReportValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
