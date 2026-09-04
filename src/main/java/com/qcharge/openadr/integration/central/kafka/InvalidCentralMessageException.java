package com.qcharge.openadr.integration.central.kafka;

/** A malformed central-service message that must be skipped instead of retried */
public class InvalidCentralMessageException extends RuntimeException {

    public InvalidCentralMessageException(String message) {
        super(message);
    }

    public InvalidCentralMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
