package com.qcharge.openadr;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public enum ApiMessage {
    FAILED_CANCEL_VEN_REGISTRATION("VEN registration cancellation failed. Response code=%s, description=%s"),

    BLANK_VENID_ERROR("venId must not be blank");

    private final String message;

    public String format(Object... args) {
        return String.format(message, args);
    }
}
