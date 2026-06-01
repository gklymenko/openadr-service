package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenAdrTransportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Truncated binary exponential backoff per OpenADR spec Section 9.1.7.
 *
 * Delay sequence: initialDelay → initialDelay*2 → initialDelay*4 → ... → maxDelay
 * 4xx errors are not retried (client error — fix the request).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final OpenAdrProperties properties;

    public <T> T executeWithRetry(String operationName, Supplier<T> operation) {
        int maxAttempts = properties.getTransport().getRetryMaxAttempts();
        long delayMillis = properties.getTransport().getRetryInitialDelayMillis();
        long maxDelayMillis = properties.getTransport().getRetryMaxDelayMillis();

        OpenAdrTransportException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (OpenAdrTransportException e) {
                if (e.isClientError()) {
                    throw e;
                }
                lastException = e;
            }

            if (attempt < maxAttempts) {
                long sleepMs = Math.min(delayMillis, maxDelayMillis);
                log.warn("OpenADR {} failed (attempt {}/{}). Retrying in {}ms...",
                        operationName, attempt, maxAttempts, sleepMs);
                sleep(sleepMs);
                delayMillis = Math.min(delayMillis * 2, maxDelayMillis);
            }
        }

        throw new OpenAdrTransportException(
                "OpenADR %s failed after %d attempts".formatted(operationName, maxAttempts),
                lastException
        );
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OpenAdrTransportException("Retry interrupted", ie);
        }
    }
}
