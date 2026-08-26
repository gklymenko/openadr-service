package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.service.report.model.ReportDataInterval;
import com.qcharge.openadr.service.report.model.ReportRidCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds TELEMETRY_USAGE XML, including usage-specific temporal properties. */
@Component
@RequiredArgsConstructor
public class TelemetryUsageReportFactory {

    private final TelemetryReportPayloadFactory payloadFactory;

    public OadrReportType build(
            ReportRequest request,
            List<ReportDataInterval> dataIntervals,
            Instant createdAt
    ) {
        requireIntervals(dataIntervals);
        Set<String> requestedRids = ReportRidCodec.decode(request.getRequestedRids());
        boolean includesEnergy = requestedRids.contains(ReportService.RID_ENERGY);
        ReportDataInterval first = dataIntervals.getFirst();
        ReportDataInterval last = dataIntervals.getLast();
        String overallDuration = includesEnergy
                ? Duration.between(first.period().start(), last.period().endExclusive()).toString()
                : null;

        var builder = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportOadrReportBuilder(
                        UUID.randomUUID().toString(),
                        request.getReportSpecifierId(),
                        request.getReportRequestId(),
                        ReportNameEnumeratedType.TELEMETRY_USAGE,
                        createdAt.toEpochMilli(),
                        first.period().start().toEpochMilli(),
                        overallDuration
                );

        for (int index = 0; index < dataIntervals.size(); index++) {
            builder.addInterval(interval(
                    String.valueOf(index),
                    dataIntervals.get(index),
                    requestedRids,
                    includesEnergy
            ));
        }
        return builder.build();
    }

    private IntervalType interval(
            String uid,
            ReportDataInterval data,
            Set<String> requestedRids,
            boolean includesEnergy
    ) {
        IntervalType interval = new IntervalType();
        interval.setDtstart(Oadr20bFactory.createDtstart(data.period().start().toEpochMilli()));
        if (includesEnergy) {
            interval.setDuration(Oadr20bFactory.createDurationPropType(
                    data.period().duration().toString()
            ));
            interval.setUid(Oadr20bFactory.createUidType(uid));
        }
        requestedRids.forEach(rid -> interval.getStreamPayloadBase().add(
                Oadr20bFactory.createOadrReportPayload(
                        payloadFactory.usage(rid, data.sample(), data.quality())
                )
        ));
        return interval;
    }

    private void requireIntervals(List<ReportDataInterval> intervals) {
        if (intervals.isEmpty()) {
            throw new IllegalStateException("A telemetry usage report must contain at least one interval");
        }
    }
}
