package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.service.report.model.ReportDataInterval;
import com.qcharge.openadr.service.report.model.ReportDataQuality;
import com.qcharge.openadr.service.report.model.ReportIntervalPlanner;
import com.qcharge.openadr.service.report.model.ReportRidCodec;
import com.qcharge.openadr.service.report.model.ReportSchedule;
import com.qcharge.openadr.service.report.telemetry.TelemetryBuffer;
import com.qcharge.openadr.service.report.telemetry.TelemetrySample;
import com.qcharge.openadr.service.report.telemetry.TelemetrySampler;
import com.qcharge.openadr.utility.TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Supplies report-neutral samples and time ranges to the XML-specific factories. */
@Component
@RequiredArgsConstructor
public class TelemetryIntervalDataProvider {

    private final TelemetryBuffer telemetryBuffer;
    private final TelemetrySampler telemetrySampler;
    private final ReportIntervalPlanner intervalPlanner;
    private final OpenAdrProperties properties;

    public List<ReportDataInterval> oneShot() {
        TelemetrySample sample = telemetrySampler.captureNow();
        return List.of(new ReportDataInterval(
                TimeRange.of(sample.capturedAt(), samplingPeriod()),
                sample,
                ReportDataQuality.GOOD
        ));
    }

    public List<ReportDataInterval> periodic(
            ReportRequest request,
            ReportSchedule schedule,
            TimeRange deliveryWindow
    ) {
        telemetrySampler.captureNow();
        Set<String> requestedRids = ReportRidCodec.decode(request.getRequestedRids());
        return schedule.granularity().isZero()
                ? changedIntervals(deliveryWindow, requestedRids)
                : fixedIntervals(deliveryWindow, schedule.granularity());
    }

    private List<ReportDataInterval> fixedIntervals(
            TimeRange deliveryWindow,
            Duration granularity
    ) {
        return intervalPlanner.split(deliveryWindow, granularity).stream()
                .map(this::intervalAt)
                .toList();
    }

    private ReportDataInterval intervalAt(TimeRange period) {
        return telemetryBuffer.latestAtOrBefore(period.endExclusive())
                .map(sample -> new ReportDataInterval(period, sample, ReportDataQuality.GOOD))
                .orElseGet(() -> new ReportDataInterval(
                        period,
                        telemetrySampler.captureNow(),
                        ReportDataQuality.BAD_NO_DATA
                ));
    }

    private List<ReportDataInterval> changedIntervals(
            TimeRange deliveryWindow,
            Set<String> requestedRids
    ) {
        List<TelemetrySample> samples = telemetryBuffer.samplesIn(deliveryWindow);
        List<ReportDataInterval> changed = new ArrayList<>();
        TelemetrySample previous = telemetryBuffer
                .latestAtOrBefore(deliveryWindow.start().minusNanos(1))
                .orElse(null);

        for (TelemetrySample sample : samples) {
            if (previous == null || valuesChanged(previous, sample, requestedRids)) {
                changed.add(new ReportDataInterval(
                        samplePeriodAt(sample.capturedAt(), deliveryWindow.endExclusive()),
                        sample,
                        ReportDataQuality.GOOD
                ));
            }
            previous = sample;
        }

        if (!changed.isEmpty()) {
            return List.copyOf(changed);
        }

        TelemetrySample fallback = telemetryBuffer.latestAtOrBefore(deliveryWindow.endExclusive())
                .orElseGet(telemetrySampler::captureNow);
        return List.of(new ReportDataInterval(
                samplePeriodAt(deliveryWindow.start(), deliveryWindow.endExclusive()),
                fallback,
                previous == null ? ReportDataQuality.BAD_NO_DATA : ReportDataQuality.NO_NEW_VALUE
        ));
    }

    private boolean valuesChanged(
            TelemetrySample previous,
            TelemetrySample current,
            Set<String> requestedRids
    ) {
        return requestedRids.stream().anyMatch(rid -> switch (rid) {
            case ReportService.RID_POWER -> Float.compare(previous.powerKw(), current.powerKw()) != 0;
            case ReportService.RID_ENERGY -> Float.compare(previous.energyKwh(), current.energyKwh()) != 0;
            case ReportService.RID_RESOURCE_STATUS ->
                    previous.online() != current.online()
                            || previous.manualOverride() != current.manualOverride()
                            || Float.compare(previous.capacityCurrent(), current.capacityCurrent()) != 0;
            default -> false;
        });
    }

    private TimeRange samplePeriodAt(Instant start, Instant maximumEnd) {
        Instant candidateEnd = start.plus(samplingPeriod());
        Instant end = candidateEnd.isBefore(maximumEnd) ? candidateEnd : maximumEnd;
        return new TimeRange(start, end);
    }

    private Duration samplingPeriod() {
        return Duration.ofSeconds(properties.getReport().getTelemetryIntervalSeconds());
    }
}
