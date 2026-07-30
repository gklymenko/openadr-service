package com.qcharge.openadr.exceptions;

import lombok.Getter;

/**
 * Represents an OpenADR application-layer error returned inside a successful
 * HTTP response.
 */
@Getter
public class OpenAdrApplicationException extends RuntimeException {

    private final int responseCode;
    private final String responseDescription;
    private final String requestId;

    public OpenAdrApplicationException(
            String message, int responseCode, String responseDescription, String requestId
    ) {
        super(message);
        this.responseCode = responseCode;
        this.responseDescription = responseDescription;
        this.requestId = requestId;
    }
}
