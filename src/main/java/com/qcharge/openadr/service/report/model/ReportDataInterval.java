package com.qcharge.openadr.service.report.model;

import com.qcharge.openadr.service.report.telemetry.TelemetrySample;
import com.qcharge.openadr.utility.TimeRange;

import java.util.Objects;

public record ReportDataInterval(
        TimeRange period,
        TelemetrySample sample,
        ReportDataQuality quality
) {

    public ReportDataInterval {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(quality, "quality");
    }
}
