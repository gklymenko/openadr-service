package com.qcharge.openadr.service.report.telemetry;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Captures telemetry at the fastest sampling rate advertised in report metadata. */
@Component
@RequiredArgsConstructor
public class TelemetrySampler {

    private final TelemetrySource source;
    private final TelemetryBuffer buffer;
    private final Clock clock;

    @PostConstruct
    void initializeBuffer() {
        captureNow();
    }

    @Scheduled(
            fixedRateString = "${openadr.report.telemetry-interval-seconds:10}",
            timeUnit = TimeUnit.SECONDS
    )
    public void sample() {
        captureNow();
    }

    public TelemetrySample captureNow() {
        Instant capturedAt = clock.instant();
        TelemetrySample sample = source.read(capturedAt);
        buffer.add(sample);
        return sample;
    }
}
