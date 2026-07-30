package com.qcharge.openadr.transport;

import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import org.junit.jupiter.api.Test;

import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER;
import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.INVALID_DATA;
import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.INVALID_ID;
import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.TARGET_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OpenAdrApplicationErrorMapperTest {

    private final OpenAdrApplicationErrorMapper mapper =
            new OpenAdrApplicationErrorMapper();

    @Test
    void existingApplicationError_preservesCodeAndAddsRequestId() {
        OadrCreateReportType request = request("request-17");
        OpenAdrApplicationException failure = new OpenAdrApplicationException(
                "Invalid report ID",
                INVALID_ID,
                "Invalid reportSpecifierID",
                null
        );

        OpenAdrApplicationException mapped = mapper.map(failure, request);

        assertEquals(INVALID_ID, mapped.getResponseCode());
        assertEquals("Invalid reportSpecifierID", mapped.getResponseDescription());
        assertEquals("request-17", mapped.getRequestId());
        assertSame(failure, mapped.getCause());
    }

    @Test
    void targetMismatch_mapsTo462() {
        OpenAdrApplicationException mapped = mapper.map(
                new TargetMismatchException("wrong VEN"),
                request("request-18")
        );

        assertEquals(TARGET_MISMATCH, mapped.getResponseCode());
        assertEquals("request-18", mapped.getRequestId());
    }

    @Test
    void illegalArgument_mapsTo454WithoutLeakingDetails() {
        OpenAdrApplicationException mapped = mapper.map(
                new IllegalArgumentException("database/internal detail"),
                request("request-19")
        );

        assertEquals(INVALID_DATA, mapped.getResponseCode());
        assertEquals(
                "OpenADR payload contains invalid data",
                mapped.getResponseDescription()
        );
    }

    @Test
    void unexpectedFailure_mapsTo459() {
        OpenAdrApplicationException mapped = mapper.map(
                new IllegalStateException("internal detail"),
                request(null)
        );

        assertEquals(COMPLIANCE_ERROR_OTHER, mapped.getResponseCode());
        assertEquals("OpenADR request could not be processed", mapped.getResponseDescription());
        assertEquals("", mapped.getRequestId());
    }

    private OadrCreateReportType request(String requestId) {
        OadrCreateReportType request = new OadrCreateReportType();
        request.setRequestID(requestId);
        return request;
    }
}
