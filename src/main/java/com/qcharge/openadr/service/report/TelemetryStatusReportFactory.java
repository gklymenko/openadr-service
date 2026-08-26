package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.service.report.model.ReportDataInterval;
import com.qcharge.openadr.service.report.model.ReportSchedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builds TELEMETRY_STATUS XML without usage-only duration properties. */
@Component
@RequiredArgsConstructor
public class TelemetryStatusReportFactory {

    private final TelemetryReportPayloadFactory payloadFactory;

    public OadrReportType oneShot(
            ReportRequest request,
            List<ReportDataInterval> dataIntervals
    ) {
        requireIntervals(dataIntervals);
        Instant capturedAt = dataIntervals.getFirst().sample().capturedAt();
        return build(request, dataIntervals, capturedAt, capturedAt, false);
    }

    public OadrReportType periodic(
            ReportRequest request,
            ReportSchedule schedule,
            List<ReportDataInterval> dataIntervals,
            Instant createdAt
    ) {
        requireIntervals(dataIntervals);
        boolean intervalTimingRequired = schedule.granularity().isZero()
                || dataIntervals.size() > 1;
        return build(
                request,
                dataIntervals,
                createdAt,
                dataIntervals.getFirst().period().start(),
                intervalTimingRequired
        );
    }

    private OadrReportType build(
            ReportRequest request,
            List<ReportDataInterval> dataIntervals,
            Instant createdAt,
            Instant overallStart,
            boolean includeIntervalTiming
    ) {
        var builder = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportOadrReportBuilder(
                        UUID.randomUUID().toString(),
                        request.getReportSpecifierId(),
                        request.getReportRequestId(),
                        ReportNameEnumeratedType.TELEMETRY_STATUS,
                        createdAt.toEpochMilli(),
                        overallStart.toEpochMilli(),
                        null
                );
        for (int index = 0; index < dataIntervals.size(); index++) {
            builder.addInterval(interval(
                    dataIntervals.get(index),
                    includeIntervalTiming,
                    index
            ));
        }
        return builder.build();
    }

    private IntervalType interval(
            ReportDataInterval data,
            boolean includeIntervalTiming,
            int sequenceNumber
    ) {
        IntervalType interval = new IntervalType();
        interval.setUid(Oadr20bFactory.createUidType(
                Integer.toString(sequenceNumber)
        ));
        if (includeIntervalTiming) {
            interval.setDtstart(Oadr20bFactory.createDtstart(
                    data.period().start().toEpochMilli()
            ));
        }
        interval.getStreamPayloadBase().add(Oadr20bFactory.createOadrReportPayload(
                payloadFactory.status(
                        data.sample(),
                        data.quality(),
                        includeIntervalTiming
                )
        ));
        return interval;
    }

    private void requireIntervals(List<ReportDataInterval> intervals) {
        if (intervals.isEmpty()) {
            throw new IllegalStateException("A telemetry status report must contain at least one interval");
        }
    }
}
