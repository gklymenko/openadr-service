package com.qcharge.openadr.service.transport;

import java.util.Objects;

/**
 * Immutable request/response pair used by transport-level correlation and ID
 * validators.
 */
public record OpenAdrExchangeContext<Q, R>(
        OpenAdrOperation<Q, R> operation,
        Q request,
        R response
) {

    public OpenAdrExchangeContext {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(request, "request");
        operation.requireValidRequest(request);
    }
}
