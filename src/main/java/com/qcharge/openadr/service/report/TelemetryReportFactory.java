package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDataQualityType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayloadResourceStatusType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
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
import java.util.UUID;

/** Maps buffered telemetry into chronologically ordered OpenADR report intervals. */
@Component
@RequiredArgsConstructor
public class TelemetryReportFactory {

    private final TelemetryBuffer telemetryBuffer;
    private final TelemetrySampler telemetrySampler;
    private final ReportIntervalPlanner intervalPlanner;
    private final OpenAdrProperties properties;

    public OadrReportType oneShot(ReportRequest request) {
        Duration samplePeriod = samplingPeriod();
        TelemetrySample sample = telemetrySampler.captureNow();
        Instant capturedAt = sample.capturedAt();
        TimeRange period = TimeRange.of(capturedAt.minus(samplePeriod), samplePeriod);
        return build(request, List.of(
                new ReportDataInterval(period, sample, ReportDataQuality.GOOD)
        ), capturedAt);
    }

    public OadrReportType periodic(
            ReportRequest request,
            ReportSchedule schedule,
            TimeRange deliveryWindow,
            Instant createdAt
    ) {
        telemetrySampler.captureNow();
        Set<String> requestedRids = ReportRidCodec.decode(request.getRequestedRids());
        List<ReportDataInterval> intervals = schedule.granularity().isZero()
                ? changedIntervals(deliveryWindow, requestedRids)
                : fixedIntervals(deliveryWindow, schedule.granularity());
        return build(request, intervals, createdAt);
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
                .map(sample -> new ReportDataInterval(
                        period,
                        sample,
                        ReportDataQuality.GOOD
                ))
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

    private OadrReportType build(
            ReportRequest request,
            List<ReportDataInterval> dataIntervals,
            Instant createdAt
    ) {
        if (dataIntervals.isEmpty()) {
            throw new IllegalStateException("A telemetry report must contain at least one interval");
        }

        Set<String> requestedRids = ReportRidCodec.decode(request.getRequestedRids());
        ReportDataInterval first = dataIntervals.getFirst();
        ReportDataInterval last = dataIntervals.getLast();
        Duration reportDuration = Duration.between(
                first.period().start(),
                last.period().endExclusive()
        );

        var builder = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportOadrReportBuilder(
                        UUID.randomUUID().toString(),
                        request.getReportSpecifierId(),
                        request.getReportRequestId(),
                        reportName(request.getReportSpecifierId()),
                        createdAt.toEpochMilli(),
                        first.period().start().toEpochMilli(),
                        reportDuration.toString()
                );

        for (int index = 0; index < dataIntervals.size(); index++) {
            builder.addInterval(toOpenAdrInterval(
                    String.valueOf(index),
                    dataIntervals.get(index),
                    requestedRids
            ));
        }
        return builder.build();
    }

    private IntervalType toOpenAdrInterval(
            String uid,
            ReportDataInterval data,
            Set<String> requestedRids
    ) {
        IntervalType interval = new IntervalType();
        interval.setUid(Oadr20bFactory.createUidType(uid));
        interval.setDtstart(Oadr20bFactory.createDtstart(data.period().start().toEpochMilli()));
        if (requestedRids.contains(ReportService.RID_ENERGY)) {
            interval.setDuration(Oadr20bFactory.createDurationPropType(
                    data.period().duration().toString()
            ));
        }

        requestedRids.forEach(rid -> interval.getStreamPayloadBase().add(
                Oadr20bFactory.createOadrReportPayload(payload(rid, data))
        ));
        return interval;
    }

    private OadrReportPayloadType payload(String rid, ReportDataInterval data) {
        OadrReportPayloadType payload = switch (rid) {
            case ReportService.RID_POWER -> Oadr20bFactory.createReportPayloadType(
                    rid, null, null, data.sample().powerKw()
            );
            case ReportService.RID_ENERGY -> Oadr20bFactory.createReportPayloadType(
                    rid, null, null, data.sample().energyKwh()
            );
            case ReportService.RID_RESOURCE_STATUS -> Oadr20bFactory.createReportPayloadType(
                    rid, null, null, resourceStatus(data.sample())
            );
            default -> throw new IllegalArgumentException("Unsupported report rID=" + rid);
        };
        payload.setOadrDataQuality(dataQuality(data.quality()).value());
        return payload;
    }

    private OadrPayloadResourceStatusType resourceStatus(TelemetrySample sample) {
        var capacity = Oadr20bFactory.createOadrLoadControlStateTypeType(
                sample.capacityCurrent(),
                sample.capacityNormal(),
                sample.capacityMin(),
                sample.capacityMax()
        );
        var loadControlState = Oadr20bFactory.createOadrLoadControlStateType(
                capacity, null, null, null
        );
        return Oadr20bFactory.createOadrPayloadResourceStatusType(
                loadControlState,
                sample.manualOverride(),
                sample.online()
        );
    }

    private OadrDataQualityType dataQuality(ReportDataQuality quality) {
        return switch (quality) {
            case GOOD -> OadrDataQualityType.QUALITY_GOOD_NON_SPECIFIC;
            case NO_NEW_VALUE -> OadrDataQualityType.NO_NEW_VALUE_PREVIOUS_VALUE_USED;
            case BAD_NO_DATA -> OadrDataQualityType.QUALITY_BAD_LAST_KNOWN_VALUE;
        };
    }

    private ReportNameEnumeratedType reportName(String reportSpecifierId) {
        return ReportService.REPORT_SPECIFIER_ID_TELEMETRY_STATUS.equals(reportSpecifierId)
                ? ReportNameEnumeratedType.TELEMETRY_STATUS
                : ReportNameEnumeratedType.TELEMETRY_USAGE;
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
