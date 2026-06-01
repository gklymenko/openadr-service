package com.qcharge.openadr.exceptions;

public class OpenAdrTransportException extends RuntimeException {

    public OpenAdrTransportException(String message) {
        super(message);
    }

    public OpenAdrTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
