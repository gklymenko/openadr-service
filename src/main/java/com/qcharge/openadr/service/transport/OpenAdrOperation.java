package com.qcharge.openadr.service.transport;

import java.util.Objects;
import java.util.Set;

/**
 * Describes one outbound OpenADR exchange.
 *
 * @param name operation name used in logs and retry diagnostics
 * @param endpoint OpenADR Simple HTTP service path
 * @param requestType payload type accepted by this operation
 * @param responseTypes non-empty response payload types accepted by this operation
 * @param responseBodyPolicy whether an empty HTTP 200 body is allowed
 */
public record OpenAdrOperation<Q, R>(
        String name,
        String endpoint,
        Class<Q> requestType,
        Set<Class<? extends R>> responseTypes,
        ResponseBodyPolicy responseBodyPolicy
) {

    public OpenAdrOperation {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(requestType, "requestType");
        responseTypes = Set.copyOf(Objects.requireNonNull(responseTypes, "responseTypes"));
        Objects.requireNonNull(responseBodyPolicy, "responseBodyPolicy");
    }

    public void requireValidRequest(Object request) {
        if (!requestType.isInstance(request)) {
            throw new IllegalArgumentException(
                    "Invalid request type for OpenADR operation %s. Expected=%s, actual=%s"
                            .formatted(name, requestType.getSimpleName(), typeName(request))
            );
        }
    }

    public boolean acceptsResponse(Object response) {
        return responseTypes.stream().anyMatch(type -> type.isInstance(response));
    }

    public boolean allowsEmptyResponse() {
        return responseBodyPolicy == ResponseBodyPolicy.OPTIONAL;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
