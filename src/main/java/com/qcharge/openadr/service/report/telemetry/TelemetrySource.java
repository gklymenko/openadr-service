package com.qcharge.openadr.service.report.telemetry;

import java.time.Instant;

/** Integration port for OCPP/device telemetry. */
public interface TelemetrySource {

    TelemetrySample read(Instant capturedAt);
}
