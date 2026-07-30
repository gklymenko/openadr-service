package com.qcharge.openadr.service.transport;

import org.springframework.stereotype.Component;

/**
 * OpenADR Simple HTTP status policy from Profile Specification section 9.1.5.
 */
@Component
public class OpenAdrHttpStatusPolicy {

    public HttpStatusAction classify(Integer httpStatusCode) {
        if (httpStatusCode == null) {
            // TCP, TLS, timeout, or another failure without an HTTP response.
            return HttpStatusAction.RETRY_AFTER_QUIESCE;
        }

        return switch (httpStatusCode) {
            case 200 -> HttpStatusAction.ACCEPT;
            case 404, 406, 501 -> HttpStatusAction.DO_NOT_RETRY;
            case 500, 503 -> HttpStatusAction.RETRY_AFTER_QUIESCE;
            default -> classifyFallback(httpStatusCode);
        };
    }

    private HttpStatusAction classifyFallback(int httpStatusCode) {
        if (httpStatusCode >= 400 && httpStatusCode < 500) {
            return HttpStatusAction.DO_NOT_RETRY;
        }
        if (httpStatusCode >= 500 && httpStatusCode < 600) {
            return HttpStatusAction.RETRY_AFTER_QUIESCE;
        }

        // OpenADR Simple HTTP defines 200 as its only successful HTTP response.
        return HttpStatusAction.DO_NOT_RETRY;
    }
}
