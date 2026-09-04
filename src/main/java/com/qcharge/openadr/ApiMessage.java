package com.qcharge.openadr;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum ApiMessage {
    FAILED_CANCEL_VEN_REGISTRATION("VEN registration cancellation failed. Response code=%s, description=%s"),

    BLANK_VENID_ERROR("venId must not be blank"),

    UNEXPECTED_VTN_PAYLOAD_TYPE("Unexpected OpenADR response type for operation=%s. Expected one of=%s, actual=%s"),
    UNEXPECTED_VTN_PAYLOAD_TYPE_DESCR("Payload not of expected type for operation=%s"),

    VTN_REJECT_OPERATION("VTN rejected OpenADR operation=%s with responseCode=%d"),

    KAFKA_MSG_INVALID_TIMESTAMP_FIELD("%s is not an ISO-8601 timestamp: %s"),
    KAFKA_MSG_FIELD_MISSING("%s is missing");

    private final String message;

    public String format(Object... args) {
        return String.format(message, args);
    }
}
