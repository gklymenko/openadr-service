package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;

import java.util.Objects;

final class OpenAdrValidationSupport {

    private OpenAdrValidationSupport() {
    }

    static EiResponseType requireEiResponse(
            EiResponseType eiResponse,
            String payloadName,
            String requestId
    ) {
        if (eiResponse == null) {
            throw missing(payloadName + ".eiResponse", requestId);
        }

        if (!hasText(eiResponse.getResponseCode())) {
            throw missing(payloadName + ".eiResponse.responseCode", requestId);
        }

        return eiResponse;
    }

    static boolean isSuccess(EiResponseType eiResponse) {
        return eiResponse != null
                && String.valueOf(ApplicationLayerErrorCodes.OK)
                .equals(eiResponse.getResponseCode());
    }

    static void validateRequestIdEcho(
            String expected,
            EiResponseType eiResponse,
            String payloadName
    ) {
        if (!hasText(expected)) {
            return;
        }

        String actual = eiResponse == null ? null : eiResponse.getRequestID();
        requireMatchingId(
                payloadName + ".eiResponse.requestID",
                expected,
                actual,
                expected
        );
    }

    static void requireMatchingId(
            String field,
            String expected,
            String actual,
            String requestId
    ) {
        requireText(actual, field, requestId);

        if (!Objects.equals(expected, actual)) {
            throw invalidId(field, expected, actual, requestId);
        }
    }

    static void validateOptionalId(
            String field,
            String expected,
            String actual,
            String requestId
    ) {
        if (hasText(actual) && hasText(expected) && !Objects.equals(expected, actual)) {
            throw invalidId(field, expected, actual, requestId);
        }
    }

    static String requireText(String value, String field, String requestId) {
        if (!hasText(value)) {
            throw missing(field, requestId);
        }

        return value;
    }

    static void require(boolean condition, String field, String requestId) {
        if (!condition) {
            throw missing(field, requestId);
        }
    }

    static OpenAdrApplicationException missing(String field, String requestId) {
        return new OpenAdrApplicationException(
                "Missing expected OpenADR information: " + field,
                ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER,
                "Missing expected information: " + field,
                requestId
        );
    }

    static OpenAdrApplicationException invalidId(
            String field,
            String expected,
            String actual,
            String requestId
    ) {
        return new OpenAdrApplicationException(
                "Unexpected OpenADR ID. field=%s, expected=%s, actual=%s"
                        .formatted(field, expected, actual),
                ApplicationLayerErrorCodes.INVALID_ID,
                "ID not as expected: " + field,
                requestId
        );
    }

    static OpenAdrApplicationException invalidData(
            String description,
            String requestId
    ) {
        return new OpenAdrApplicationException(
                "Invalid OpenADR data: " + description,
                ApplicationLayerErrorCodes.INVALID_DATA,
                description,
                requestId
        );
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
