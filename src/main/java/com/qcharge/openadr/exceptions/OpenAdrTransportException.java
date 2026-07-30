package com.qcharge.openadr.exceptions;

/**
 * Represents a non-HTTP transport/protocol failure, such as XML marshalling,
 * unmarshalling, an empty required response, or an unexpected payload type.
 */
public class OpenAdrTransportException extends RuntimeException {

    public OpenAdrTransportException(String message) {
        super(message);
    }

    public OpenAdrTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
