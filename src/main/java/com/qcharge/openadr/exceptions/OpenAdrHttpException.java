package com.qcharge.openadr.exceptions;

import lombok.Getter;
import org.springframework.web.client.HttpServerErrorException;

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

    public OpenAdrHttpException(String message, int httpStatusCode, Throwable cause) {
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
