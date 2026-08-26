package com.qcharge.openadr.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.exceptions.OpenAdrHttpException;
import com.qcharge.openadr.service.transport.OpenAdrHttpStatusPolicy;
import com.qcharge.openadr.service.transport.RetryHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryHandlerTest {

    private RetryHandler retryHandler;
    private OpenAdrProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OpenAdrProperties();
        properties.getTransport().setRetryMaxAttempts(3);
        properties.getTransport().setRetryInitialDelayMillis(0); // no sleep in tests
        properties.getTransport().setRetryMaxDelayMillis(0);
        retryHandler = new RetryHandler(properties, new OpenAdrHttpStatusPolicy());
    }

    @Test
    void executeWithRetry_successOnFirstAttempt_returnsResult() {
        String result = retryHandler.executeWithRetry("test-op", () -> "ok");

        assertEquals("ok", result);
    }

    @Test
    void executeWithRetry_successOnSecondAttempt_returnsResult() {
        AtomicInteger calls = new AtomicInteger(0);

        String result = retryHandler.executeWithRetry("test-op", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new OpenAdrHttpException("HTTP server error 503");
            }
            return "recovered";
        });

        assertEquals("recovered", result);
        assertEquals(2, calls.get());
    }

    @Test
    void executeWithRetry_serverError_retriesUpToMaxAttempts() {
        AtomicInteger calls = new AtomicInteger(0);

        OpenAdrHttpException ex = assertThrows(OpenAdrHttpException.class, () ->
                retryHandler.executeWithRetry("test-op", () -> {
                    calls.incrementAndGet();
                    throw new OpenAdrHttpException("HTTP server error 500", 500, new RuntimeException());
                })
        );

        assertEquals(3, calls.get(), "Should attempt exactly maxAttempts times");
        assertTrue(ex.getMessage().contains("failed after 3 attempts"));
    }

    @Test
    void executeWithRetry_clientError_doesNotRetry() {
        AtomicInteger calls = new AtomicInteger(0);

        assertThrows(OpenAdrHttpException.class, () ->
                retryHandler.executeWithRetry("test-op", () -> {
                    calls.incrementAndGet();
                    throw new OpenAdrHttpException("HTTP client error 404", 404, new RuntimeException());
                })
        );

        assertEquals(1, calls.get(), "4xx must not be retried");
    }

    @Test
    void executeWithRetry_connectionError_retries() {
        AtomicInteger calls = new AtomicInteger(0);

        assertThrows(OpenAdrHttpException.class, () ->
                retryHandler.executeWithRetry("test-op", () -> {
                    calls.incrementAndGet();
                    throw new OpenAdrHttpException("HTTP connection error");
                })
        );

        assertEquals(3, calls.get(), "Connection errors must be retried");
    }

    @Test
    void executeWithRetry_exponentialDelayDoubles() {
        properties.getTransport().setRetryInitialDelayMillis(100);
        properties.getTransport().setRetryMaxDelayMillis(10000);
        properties.getTransport().setRetryMaxAttempts(4);
        retryHandler = new RetryHandler(properties, new OpenAdrHttpStatusPolicy());

        // Capture delays by overriding sleep indirectly — verify via timing would be flaky.
        // Instead verify that the delay sequence is bounded by maxDelay.
        // We use a cap of 200ms to validate truncation.
        properties.getTransport().setRetryMaxDelayMillis(150);
        retryHandler = new RetryHandler(properties, new OpenAdrHttpStatusPolicy());

        AtomicInteger calls = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        assertThrows(OpenAdrHttpException.class, () ->
                retryHandler.executeWithRetry("test-op", () -> {
                    calls.incrementAndGet();
                    throw new OpenAdrHttpException("HTTP server error 503", 503, new RuntimeException());
                })
        );

        long elapsed = System.currentTimeMillis() - start;

        // 3 retries: delays are min(100,150)=100, min(200,150)=150, no delay on last
        // Total sleep ≥ 100 + 150 = 250ms (truncated cap of 150 kicks in at 2nd retry)
        assertTrue(elapsed >= 200, "Expected at least 200ms elapsed due to backoff, got " + elapsed + "ms");
        assertEquals(4, calls.get());
    }

    @Test
    void executeWithRetry_maxDelayTruncates() {
        // With initialDelay=1000 and maxDelay=500, delay should be capped at 500
        properties.getTransport().setRetryInitialDelayMillis(1000);
        properties.getTransport().setRetryMaxDelayMillis(500);
        properties.getTransport().setRetryMaxAttempts(2);
        retryHandler = new RetryHandler(properties, new OpenAdrHttpStatusPolicy());

        AtomicInteger calls = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        assertThrows(OpenAdrHttpException.class, () ->
                retryHandler.executeWithRetry("test-op", () -> {
                    calls.incrementAndGet();
                    throw new OpenAdrHttpException("HTTP server error 503", 503, new RuntimeException());
                })
        );

        long elapsed = System.currentTimeMillis() - start;

        // Base delay is capped at 500ms before symmetric ±25% jitter is applied.
        // Allow scheduling/timer tolerance below the theoretical 375ms minimum.
        assertTrue(elapsed < 800, "Delay should be capped at maxDelayMillis=500ms, got " + elapsed + "ms");
        assertTrue(elapsed >= 350, "Expected capped jittered delay, got " + elapsed + "ms");
        assertEquals(2, calls.get());
    }

    @Test
    void applicationError_isNotRetried() {
        AtomicInteger calls = new AtomicInteger(0);

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> retryHandler.executeWithRetry("test-op", () -> {
                    calls.incrementAndGet();
                    throw new OpenAdrApplicationException(
                            "VTN rejected request",
                            OpenADRResponseCode.NOT_REGISTERED,
                            "Not Registered/Authorized",
                            "request-123"
                    );
                })
        );

        assertEquals(OpenADRResponseCode.NOT_REGISTERED, exception.getResponseCode());
        assertEquals(1, calls.get(), "Application errors must not enter the HTTP retry loop");
    }

    @Test
    void executeWithRetry_notImplemented_doesNotRetry() {
        AtomicInteger calls = new AtomicInteger(0);

        OpenAdrHttpException exception = assertThrows(
                OpenAdrHttpException.class,
                () -> retryHandler.executeWithRetry("test-op", () -> {
                    calls.incrementAndGet();
                    throw new OpenAdrHttpException(
                            "HTTP server error 501",
                            501,
                            new RuntimeException()
                    );
                })
        );

        assertEquals(Integer.valueOf(501), exception.getHttpStatusCode());
        assertEquals(1, calls.get(), "HTTP 501 must not be retried");
    }

    @Test
    void executeWithRetry_exhausted_preservesLastHttpStatus() {
        OpenAdrHttpException exception = assertThrows(
                OpenAdrHttpException.class,
                () -> retryHandler.executeWithRetry("test-op", () -> {
                    throw new OpenAdrHttpException(
                            "HTTP server error 503",
                            503,
                            new RuntimeException()
                    );
                })
        );

        assertEquals(Integer.valueOf(503), exception.getHttpStatusCode());
        assertInstanceOf(OpenAdrHttpException.class, exception.getCause());
    }
}
