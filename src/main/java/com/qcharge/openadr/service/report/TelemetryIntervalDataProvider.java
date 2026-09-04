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
import com.qcharge.openadr.utility.TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Supplies resource-scoped persisted samples and time ranges to the XML factories. */
@Component
@RequiredArgsConstructor
public class TelemetryIntervalDataProvider {

    private final TelemetryBuffer telemetryBuffer;
    private final ReportIntervalPlanner intervalPlanner;
    private final OpenAdrProperties properties;
    private final Clock clock;

    public List<ReportDataInterval> oneShot(ReportRequest request) {
        var persisted = telemetryBuffer.latest(request.getResourceId());
        TelemetrySample sample = persisted.orElseGet(() -> noData(clock.instant()));
        return List.of(new ReportDataInterval(
                TimeRange.of(sample.capturedAt(), samplingPeriod()),
                sample,
                persisted.isPresent() ? ReportDataQuality.GOOD : ReportDataQuality.BAD_NO_DATA
        ));
    }

    public List<ReportDataInterval> periodic(
            ReportRequest request,
            ReportSchedule schedule,
            TimeRange deliveryWindow
    ) {
        Set<String> requestedRids = ReportRidCodec.decode(request.getRequestedRids());
        return schedule.granularity().isZero()
                ? changedIntervals(request.getResourceId(), deliveryWindow, requestedRids)
                : fixedIntervals(request.getResourceId(), deliveryWindow, schedule.granularity());
    }

    private List<ReportDataInterval> fixedIntervals(
            String resourceId,
            TimeRange deliveryWindow,
            Duration granularity
    ) {
        return intervalPlanner.split(deliveryWindow, granularity).stream()
                .map(period -> intervalAt(resourceId, period))
                .toList();
    }

    private ReportDataInterval intervalAt(String resourceId, TimeRange period) {
        return telemetryBuffer.latestAtOrBefore(resourceId, period.endExclusive())
                .map(sample -> new ReportDataInterval(period, sample, ReportDataQuality.GOOD))
                .orElseGet(() -> new ReportDataInterval(
                        period,
                        noData(period.endExclusive()),
                        ReportDataQuality.BAD_NO_DATA
                ));
    }

    private List<ReportDataInterval> changedIntervals(
            String resourceId,
            TimeRange deliveryWindow,
            Set<String> requestedRids
    ) {
        List<TelemetrySample> samples = telemetryBuffer.samplesIn(resourceId, deliveryWindow);
        List<ReportDataInterval> changed = new ArrayList<>();
        TelemetrySample previous = telemetryBuffer
                .latestAtOrBefore(resourceId, deliveryWindow.start().minusNanos(1))
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

        var persisted = telemetryBuffer.latestAtOrBefore(resourceId, deliveryWindow.endExclusive());
        TelemetrySample fallback = persisted.orElseGet(() -> noData(deliveryWindow.start()));
        return List.of(new ReportDataInterval(
                samplePeriodAt(deliveryWindow.start(), deliveryWindow.endExclusive()),
                fallback,
                persisted.isEmpty() ? ReportDataQuality.BAD_NO_DATA : ReportDataQuality.NO_NEW_VALUE
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

    private TelemetrySample noData(Instant capturedAt) {
        return new TelemetrySample(
                capturedAt,
                0.0f,
                0.0f,
                false,
                false,
                0.0f,
                1.0f,
                0.0f,
                1.0f
        );
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
