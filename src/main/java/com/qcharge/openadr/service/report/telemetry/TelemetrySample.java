package com.qcharge.openadr.service.report.telemetry;

import java.time.Instant;
import java.util.Objects;

/** One immutable device snapshot captured independently of report delivery. */
public record TelemetrySample(
        Instant capturedAt,
        float powerKw,
        float energyKwh,
        boolean online,
        boolean manualOverride,
        float capacityCurrent,
        float capacityMax,
        float capacityMin,
        float capacityNormal
) {
    public TelemetrySample {
        Objects.requireNonNull(capturedAt, "capturedAt");
    }
}
