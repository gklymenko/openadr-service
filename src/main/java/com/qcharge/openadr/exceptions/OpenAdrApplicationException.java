package com.qcharge.openadr.exceptions;

import com.qcharge.openadr.service.transport.ApplicationErrorAction;
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
    private final String operationName;
    private final ApplicationErrorAction action;

    public OpenAdrApplicationException(
            String message, int responseCode, String responseDescription, String requestId
    ) {
        this(
                message,
                responseCode,
                responseDescription,
                requestId,
                null,
                ApplicationErrorAction.FAIL_OPERATION
        );
    }

    public OpenAdrApplicationException(
            String message,
            int responseCode,
            String responseDescription,
            String requestId,
            String operationName,
            ApplicationErrorAction action
    ) {
        super(message);
        this.responseCode = responseCode;
        this.responseDescription = responseDescription;
        this.requestId = requestId;
        this.operationName = operationName;
        this.action = action;
    }
}
