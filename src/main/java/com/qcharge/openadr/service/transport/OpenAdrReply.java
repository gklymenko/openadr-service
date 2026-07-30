package com.qcharge.openadr.service.transport;

import java.util.Objects;

/**
 * An OpenADR payload together with the outbound operation used to send it.
 */
public record OpenAdrReply<Q, R>(
        OpenAdrOperation<Q, R> operation,
        Q payload
) {

    public OpenAdrReply {
        Objects.requireNonNull(operation, "operation");
        operation.requireValidRequest(payload);
    }
}
