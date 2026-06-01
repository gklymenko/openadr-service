package com.qcharge.openadr.exceptions;

import lombok.Getter;

@Getter
public class OpenAdrTransportException extends RuntimeException {

    /** null when the failure is not an HTTP-level error (e.g. connection refused, marshal error). */
    private final Integer httpStatusCode;

    public OpenAdrTransportException(String message) {
        super(message);
        this.httpStatusCode = null;
    }

    public OpenAdrTransportException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = null;
    }

    public OpenAdrTransportException(String message, int httpStatusCode, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = httpStatusCode;
    }

    public boolean isClientError() {
        return httpStatusCode != null && httpStatusCode >= 400 && httpStatusCode < 500;
    }

    public boolean isServerError() {
        return httpStatusCode != null && httpStatusCode >= 500;
    }
}