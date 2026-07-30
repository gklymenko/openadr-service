package com.qcharge.openadr.exceptions;

import lombok.Getter;

/**
 * Represents an HTTP exchange failure.
 *
 * <p>The status is {@code null} when no HTTP response was received, for example
 * when the connection timed out or was refused.</p>
 */
@Getter
public class OpenAdrHttpException extends OpenAdrTransportException {

    private final Integer httpStatusCode;

    public OpenAdrHttpException(String message) {
        super(message);
        this.httpStatusCode = null;
    }

    public OpenAdrHttpException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = null;
    }

    public OpenAdrHttpException(String message, Integer httpStatusCode, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = httpStatusCode;
    }
}
