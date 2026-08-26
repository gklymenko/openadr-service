package com.qcharge.openadr.service.report.telemetry;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** Safe fallback until the OCPP telemetry adapter is connected to {@link TelemetrySource}. */
@Component
public class DefaultTelemetrySource implements TelemetrySource {

    @Override
    public TelemetrySample read(Instant capturedAt) {
        return new TelemetrySample(
                capturedAt,
                0.0f,
                0.0f,
                true,
                false,
                1.0f,
                1.0f,
                0.0f,
                1.0f
        );
    }
}
