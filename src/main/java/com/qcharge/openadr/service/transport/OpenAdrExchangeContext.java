package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;

import java.util.Objects;

/**
 * Immutable request/response pair used by transport-level correlation and ID
 * validators.
 */
public record OpenAdrExchangeContext<Q, R>(
        OpenAdrOperation<Q, R> operation,
        OpenAdrSessionSnapshot session,
        Q request,
        R response
) {

    public OpenAdrExchangeContext {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(request, "request");
        operation.requireValidRequest(request);
    }
}
