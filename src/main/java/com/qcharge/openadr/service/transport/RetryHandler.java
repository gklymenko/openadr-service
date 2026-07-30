package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenAdrHttpException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Truncated binary exponential backoff per OpenADR spec Section 9.1.7.
 *
 * Delay sequence: initialDelay → initialDelay*2 → initialDelay*4 → ... → maxDelay
 * Retry decisions are delegated to {@link OpenAdrHttpStatusPolicy}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final OpenAdrProperties properties;
    private final OpenAdrHttpStatusPolicy httpStatusPolicy;

    public <T> T executeWithRetry(String operationName, Supplier<T> operation) {
        int maxAttempts = properties.getTransport().getRetryMaxAttempts();
        long delayMillis = properties.getTransport().getRetryInitialDelayMillis();
        long maxDelayMillis = properties.getTransport().getRetryMaxDelayMillis();

        OpenAdrHttpException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (OpenAdrHttpException e) {
                HttpStatusAction action = httpStatusPolicy.classify(e.getHttpStatusCode());
                if (action != HttpStatusAction.RETRY_AFTER_QUIESCE) {
                    throw e;
                }
                lastException = e;
            }

            if (attempt < maxAttempts) {
                long sleepMs = withJitter(Math.min(delayMillis, maxDelayMillis));

                log.warn("OpenADR {} failed (attempt {}/{}). Retrying in {}ms...",
                        operationName, attempt, maxAttempts, sleepMs);
                sleep(sleepMs);
                delayMillis = Math.min(delayMillis * 2, maxDelayMillis);
            }
        }

        throw new OpenAdrHttpException(
                "OpenADR %s failed after %d attempts".formatted(operationName, maxAttempts),
                lastException != null ? lastException.getHttpStatusCode() : null,
                lastException
        );
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OpenAdrHttpException("Retry interrupted", ie);
        }
    }

    private long withJitter(long baseDelayMillis) {
        long jitter = Math.max(1, baseDelayMillis / 4);
        long min = Math.max(0, baseDelayMillis - jitter);
        long max = baseDelayMillis + jitter;

        return java.util.concurrent.ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
